package ru.daybook.app.data

/** Three-way merge for visual boards, with explicit conflict detection. */
object DaybookBoardSyncMerger {
    data class Result(
        val boards: List<DaybookEntry>?,
        val conflicts: List<String>
    ) {
        val succeeded: Boolean get() = boards != null && conflicts.isEmpty()
    }

    fun merge(
        base: List<DaybookEntry>,
        local: List<DaybookEntry>,
        remote: List<DaybookEntry>
    ): Result {
        val baseMap = base.associateBy { it.id }
        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val ids = linkedSetOf<String>().apply {
            addAll(baseMap.keys)
            addAll(localMap.keys)
            addAll(remoteMap.keys)
        }
        val merged = mutableListOf<DaybookEntry>()
        val conflicts = mutableListOf<String>()

        for (id in ids) {
            val before = baseMap[id]
            val localValue = localMap[id]
            val remoteValue = remoteMap[id]
            val chosen = when {
                localValue == remoteValue -> localValue
                localValue == before -> remoteValue
                remoteValue == before -> localValue
                else -> {
                    conflicts += "board:$id"
                    null
                }
            }
            if (chosen != null) merged += chosen
        }

        return if (conflicts.isEmpty()) {
            Result(merged.sortedBy { it.id }, emptyList())
        } else {
            Result(null, conflicts)
        }
    }
}
