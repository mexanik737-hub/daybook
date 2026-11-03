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
        val baseline = baselineJson?.let(DaybookJson::decode)

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
            "Синхронизация завершена. Изменения с устройств объединены без потери независимых записей."
        )
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
}
