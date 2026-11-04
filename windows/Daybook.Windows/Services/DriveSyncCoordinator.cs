using System.Text.Json;
using Daybook.Windows.Models;

namespace Daybook.Windows.Services;

public sealed class DriveSyncCoordinator
{
    private readonly LocalStore _localStore;
    private readonly SyncStateStore _stateStore;
    private readonly GoogleDriveApi _drive;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = false
    };

    public DriveSyncCoordinator(LocalStore? localStore = null, SyncStateStore? stateStore = null, GoogleDriveApi? drive = null)
    {
        _localStore = localStore ?? new LocalStore();
        _stateStore = stateStore ?? new SyncStateStore();
        _drive = drive ?? new GoogleDriveApi();
    }

    public sealed record SyncOutcome(string Status, string Message, IReadOnlyList<string>? Conflicts = null);

    public async Task<SyncOutcome> SyncAsync(string accessToken, CancellationToken cancellationToken = default)
    {
        var local = Normalize(await _localStore.LoadAsync());
        var state = await _stateStore.LoadAsync();
        var folderId = await _drive.GetOrCreateDaybookFolderAsync(accessToken, cancellationToken);
        var remoteFile = await _drive.FindDataFileAsync(folderId, accessToken, cancellationToken);

        if (remoteFile is null)
        {
            if (!string.IsNullOrWhiteSpace(state.BaselineJson))
            {
                return new SyncOutcome(
                    "conflict",
                    "Файл daybook-data.json исчез из Google Drive после предыдущей синхронизации. Автоматически создавать его заново небезопасно.");
            }

            var created = await _drive.UploadDocumentAsync(
                null, folderId, local, accessToken, cancellationToken: cancellationToken);
            await SaveBaselineAsync(created.Id, local);
            return new SyncOutcome("uploaded", "Создан файл Daybook на Google Drive.");
        }

        var remote = Normalize(await _drive.DownloadDocumentAsync(remoteFile, accessToken, cancellationToken));
        var baseline = string.IsNullOrWhiteSpace(state.BaselineJson)
            ? null
            : Deserialize(state.BaselineJson);

        if (baseline is null)
        {
            if (IsEmpty(local) && !IsEmpty(remote))
            {
                await _localStore.SaveAsync(remote);
                await SaveBaselineAsync(remoteFile.Id, remote);
                return new SyncOutcome("downloaded", "Данные загружены из Google Drive.");
            }

            if (IsEmpty(remote) && !IsEmpty(local))
            {
                var uploaded = await _drive.UploadDocumentAsync(
                    remoteFile.Id, folderId, local, accessToken, remoteFile.ETag, cancellationToken);
                await SaveBaselineAsync(uploaded.Id, local);
                return new SyncOutcome("uploaded", "Локальные данные сохранены в Google Drive.");
            }

            if (DocumentsEqual(local, remote))
            {
                await SaveBaselineAsync(remoteFile.Id, remote);
                return new SyncOutcome("unchanged", "Локальные данные уже совпадают с Google Drive.");
            }

            return new SyncOutcome(
                "conflict",
                "На этом устройстве и в Google Drive уже есть разные данные, а общей базовой версии ещё нет. Ничего не перезаписано.");
        }

        var merge = DaybookSyncMerger.Merge(baseline, local, remote);
        if (!merge.Succeeded || merge.Document is null)
        {
            return new SyncOutcome(
                "conflict",
                "Одни и те же записи изменены на разных устройствах. Изменения сохранены локально; автоматическая запись остановлена.",
                merge.Conflicts);
        }

        var merged = Normalize(merge.Document);
        if (!DocumentsEqual(local, merged))
            await _localStore.SaveAsync(merged);

        var finalFile = remoteFile;
        if (!DocumentsEqual(remote, merged))
        {
            try
            {
                finalFile = await _drive.UploadDocumentAsync(
                    remoteFile.Id, folderId, merged, accessToken, remoteFile.ETag, cancellationToken);
            }
            catch (GoogleDriveException ex) when (ex.StatusCode == 412)
            {
                return new SyncOutcome(
                    "conflict",
                    "Файл в Google Drive изменился прямо во время синхронизации. Повтори синхронизацию; текущие данные не были перезаписаны.");
            }
        }

        await SaveBaselineAsync(finalFile.Id, merged);
        return new SyncOutcome(
            DocumentsEqual(local, merged) && DocumentsEqual(remote, merged) ? "unchanged" : "merged",
            "Синхронизация завершена. Изменения с устройств объединены.");
    }

    private async Task SaveBaselineAsync(string fileId, DaybookDocument document)
    {
        await _stateStore.SaveAsync(new SyncStateStore.SyncState
        {
            RemoteFileId = fileId,
            BaselineJson = Serialize(document),
            LastSuccessfulSync = DateTimeOffset.UtcNow
        });
    }

    private static DaybookDocument Deserialize(string json) =>
        JsonSerializer.Deserialize<DaybookDocument>(json)
        ?? throw new InvalidOperationException("Не удалось прочитать локальную базовую версию синхронизации.");

    private static bool IsEmpty(DaybookDocument document) =>
        document.Tasks.Count == 0 && document.Notes.Count == 0;

    private static DaybookDocument Normalize(DaybookDocument document) => new()
    {
        Tasks = document.Tasks.OrderBy(x => x.Id, StringComparer.Ordinal).ToList(),
        Notes = document.Notes.OrderBy(x => x.Id, StringComparer.Ordinal).ToList()
    };

    private static string Serialize(DaybookDocument document) => JsonSerializer.Serialize(Normalize(document), JsonOptions);

    private static bool DocumentsEqual(DaybookDocument left, DaybookDocument right) =>
        string.Equals(Serialize(left), Serialize(right), StringComparison.Ordinal);
}
