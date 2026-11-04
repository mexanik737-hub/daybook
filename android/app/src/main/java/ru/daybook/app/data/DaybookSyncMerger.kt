package ru.daybook.app.data

/**
 * Three-way, per-entry merge for two clients editing the same Drive document.
 *
 * A deletion is represented by an entry being absent from a snapshot. If the
 * other client changed that entry after the base snapshot, the merge reports a
 * conflict instead of silently resurrecting or discarding it.
 */
object DaybookSyncMerger {
    data class Result(
        val document: DaybookDocument?,
        val conflicts: List<String>
    ) {
        val succeeded: Boolean get() = document != null && conflicts.isEmpty()
    }

    fun merge(
        base: DaybookDocument,
        local: DaybookDocument,
        remote: DaybookDocument
    ): Result {
        val conflicts = mutableListOf<String>()

        fun mergeKind(
            kind: String,
            baseEntries: List<DaybookEntry>,
            localEntries: List<DaybookEntry>,
            remoteEntries: List<DaybookEntry>
        ): List<DaybookEntry> {
            val baseMap = baseEntries.associateBy { it.id }
            val localMap = localEntries.associateBy { it.id }
            val remoteMap = remoteEntries.associateBy { it.id }
            val ids = linkedSetOf<String>().apply {
                addAll(baseMap.keys)
                addAll(localMap.keys)
                addAll(remoteMap.keys)
            }
            val merged = mutableListOf<DaybookEntry>()

            for (id in ids) {
                val before = baseMap[id]
                val localValue = localMap[id]
                val remoteValue = remoteMap[id]
                val chosen = when {
                    localValue == remoteValue -> localValue
                    localValue == before -> remoteValue
                    remoteValue == before -> localValue
                    else -> {
                        conflicts += "$kind:$id"
                        null
                    }
                }
                if (chosen != null) merged += chosen
            }
            return merged.sortedByDescending { it.updated }
        }

        val tasks = mergeKind("task", base.tasks, local.tasks, remote.tasks)
        val notes = mergeKind("note", base.notes, local.notes, remote.notes)

        return if (conflicts.isEmpty()) {
            Result(DaybookDocument(tasks = tasks, notes = notes), emptyList())
        } else {
            Result(null, conflicts)
        }
    }
}
