package ru.daybook.app.data

class DaybookDriveSyncCoordinator(
    private val repository: DaybookRepository,
    private val stateStore: DaybookSyncStateStore,
    private val drive: GoogleDriveApi = GoogleDriveApi()
) {
    data class Result(
        val status: String,
        val message: String,
        val conflicts: List<String> = emptyList()
    )

    suspend fun sync(accessToken: String): Result {
        val entriesResult = syncEntries(accessToken)
        val boardsResult = syncBoards(accessToken)
        val conflicts = entriesResult.conflicts + boardsResult.conflicts
        if (entriesResult.status == "conflict" || boardsResult.status == "conflict") {
            return Result(
                "conflict",
                listOf(entriesResult.message, boardsResult.message).distinct().joinToString(" "),
                conflicts
            )
        }
        val changed = listOf(entriesResult, boardsResult).filter { it.status !in setOf("unchanged", "empty") }
        return Result(
            if (changed.isEmpty()) "unchanged" else changed.last().status,
            listOf(entriesResult.message, boardsResult.message).distinct().joinToString(" ")
        )
    }

    private suspend fun syncEntries(accessToken: String): Result {
        val local = normalize(repository.exportDocument())
        val baselineJson = stateStore.baselineJson()
        val folderId = drive.getOrCreateDaybookFolder(accessToken)
        val remoteFile = drive.findDataFile(folderId, accessToken)

        if (remoteFile == null) {
            if (baselineJson != null) {
                return Result(
                    "conflict",
                    "Файл daybook-data.json исчез из Google Drive после предыдущей синхронизации. Автоматически создавать его заново небезопасно."
                )
            }
            val created = drive.uploadDocument(null, folderId, local, accessToken)
            saveBaseline(created.id, local)
            return Result("uploaded", "Создан файл Daybook на Google Drive.")
        }

        val remote = normalize(drive.downloadDocument(remoteFile, accessToken))
        val baseline = baselineJson?.let { DaybookJson.decode(it) }

        if (baseline == null) {
            when {
                isEmpty(local) && !isEmpty(remote) -> {
                    repository.importDocument(remote)
                    saveBaseline(remoteFile.id, remote)
                    return Result("downloaded", "Данные загружены из Google Drive.")
                }
                isEmpty(remote) && !isEmpty(local) -> {
                    val uploaded = drive.uploadDocument(
                        remoteFile.id, folderId, local, accessToken, remoteFile.etag
                    )
                    saveBaseline(uploaded.id, local)
                    return Result("uploaded", "Локальные данные сохранены в Google Drive.")
                }
                documentsEqual(local, remote) -> {
                    saveBaseline(remoteFile.id, remote)
                    return Result("unchanged", "Локальные данные уже совпадают с Google Drive.")
                }
                else -> return Result(
                    "conflict",
                    "На этом устройстве и в Google Drive уже есть разные данные, а общей базовой версии ещё нет. Ничего не перезаписано."
                )
            }
        }

        val merge = DaybookSyncMerger.merge(baseline, local, remote)
        if (!merge.succeeded || merge.document == null) {
            return Result(
                "conflict",
                "Одни и те же записи изменены на разных устройствах. Изменения сохранены локально; автоматическая запись остановлена.",
                merge.conflicts
            )
        }

        val merged = normalize(merge.document)
        if (!documentsEqual(local, merged)) repository.importDocument(merged)

        var finalFile = remoteFile
        if (!documentsEqual(remote, merged)) {
            try {
                finalFile = drive.uploadDocument(
                    remoteFile.id, folderId, merged, accessToken, remoteFile.etag
                )
            } catch (error: DriveApiException) {
                if (error.statusCode == 412) {
                    return Result(
                        "conflict",
                        "Файл в Google Drive изменился прямо во время синхронизации. Повтори синхронизацию; текущие данные не были перезаписаны."
                    )
                }
                throw error
            }
        }

        saveBaseline(finalFile.id, merged)
        return Result(
            if (documentsEqual(local, merged) && documentsEqual(remote, merged)) "unchanged" else "merged",
            "Синхронизация завершена. Изменения с устройств объединены."
        )
    }

    private suspend fun syncBoards(accessToken: String): Result {
        val local = repository.exportBoards().sortedBy { it.id }
        val baselineJson = stateStore.boardsBaselineJson()
        val folderId = drive.getOrCreateDaybookFolder(accessToken)
        val remoteFile = drive.findFile(folderId, BOARDS_FILE_NAME, accessToken)
        val remote = remoteFile?.let {
            decodeBoards(drive.downloadText(it, accessToken))
        } ?: BoardSyncDocument()
        val baseline = baselineJson?.let(::decodeBoards)

        if (remoteFile == null) {
            if (baseline != null && baseline.boards.isNotEmpty()) {
                return Result(
                    "conflict",
                    "Файл daybook-boards.json исчез из Google Drive. Локальные схемы сохранены; автоматическое восстановление остановлено."
                )
            }
            if (local.isEmpty()) {
                stateStore.saveBoardsBaseline(encodeBoards(BoardSyncDocument()))
                return Result("empty", "Схем пока нет — отдельный файл для них не требуется.")
            }
            val created = drive.uploadText(
                null, folderId, BOARDS_FILE_NAME, encodeBoards(BoardSyncDocument(local)), accessToken
            )
            stateStore.saveBoardsBaseline(encodeBoards(BoardSyncDocument(local)))
            return Result("uploaded", "Схемы сохранены в отдельный файл Google Drive.")
        }

        if (baseline == null) {
            when {
                local.isEmpty() && remote.boards.isNotEmpty() -> {
                    repository.importBoards(remote.boards)
                    stateStore.saveBoardsBaseline(encodeBoards(remote))
                    return Result("downloaded", "Схемы загружены из Google Drive.")
                }
                remote.boards.isEmpty() && local.isNotEmpty() -> {
                    val uploaded = drive.uploadText(
                        remoteFile.id, folderId, BOARDS_FILE_NAME,
                        encodeBoards(BoardSyncDocument(local)), accessToken, remoteFile.etag
                    )
                    stateStore.saveBoardsBaseline(encodeBoards(BoardSyncDocument(local)))
                    return Result("uploaded", "Локальные схемы сохранены в Google Drive.")
                }
                local == remote.boards -> {
                    stateStore.saveBoardsBaseline(encodeBoards(remote))
                    return Result("unchanged", "Схемы уже совпадают с Google Drive.")
                }
                else -> return Result(
                    "conflict",
                    "На устройстве и в Google Drive уже есть разные схемы, а общей базовой версии ещё нет. Ничего не перезаписано."
                )
            }
        }

        val merge = mergeBoards(baseline.boards, local, remote.boards)
        if (merge.second.isNotEmpty()) {
            return Result(
                "conflict",
                "Одна или несколько схем изменены на разных устройствах. Локальные изменения сохранены; автоматическая запись остановлена.",
                merge.second
            )
        }
        val merged = BoardSyncDocument(merge.first.sortedBy { it.id })
        if (local != merged.boards) repository.importBoards(merged.boards)
        var finalFile = remoteFile
        if (remote.boards != merged.boards) {
            try {
                finalFile = drive.uploadText(
                    remoteFile.id, folderId, BOARDS_FILE_NAME,
                    encodeBoards(merged), accessToken, remoteFile.etag
                )
            } catch (error: DriveApiException) {
                if (error.statusCode == 412) {
                    return Result(
                        "conflict",
                        "Файл схем в Google Drive изменился во время синхронизации. Повтори попытку; данные не были перезаписаны."
                    )
                }
                throw error
            }
        }
        stateStore.saveBoardsBaseline(encodeBoards(merged))
        return Result(
            if (local == merged.boards && remote.boards == merged.boards) "unchanged" else "merged",
            "Схемы синхронизированы с Google Drive."
        )
    }

    private fun decodeBoards(json: String): BoardSyncDocument =
        DaybookJson.format.decodeFromString(json)

    private fun encodeBoards(document: BoardSyncDocument): String =
        DaybookJson.format.encodeToString(document)

    private fun mergeBoards(
        base: List<DaybookEntry>,
        local: List<DaybookEntry>,
        remote: List<DaybookEntry>
    ): Pair<List<DaybookEntry>, List<String>> {
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
        ids.forEach { id ->
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
        return merged to conflicts
    }

    private fun saveBaseline(fileId: String, document: DaybookDocument) {
        stateStore.save(fileId, DaybookJson.encode(normalize(document)))
    }

    private fun isEmpty(document: DaybookDocument) =
        document.tasks.isEmpty() && document.notes.isEmpty()

    private fun normalize(document: DaybookDocument) = DaybookDocument(
        tasks = document.tasks.sortedBy { it.id },
        notes = document.notes.sortedBy { it.id }
    )

    private fun documentsEqual(left: DaybookDocument, right: DaybookDocument) =
        normalize(left) == normalize(right)

    private companion object {
        const val BOARDS_FILE_NAME = "daybook-boards.json"
    }
}
