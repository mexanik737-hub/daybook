using System.IO;
using System.Text.Json;

namespace Daybook.Windows.Services;

public sealed class SyncStateStore
{
    private readonly string _path = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Daybook", "drive-sync-state.json");

    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public sealed class SyncState
    {
        public string? RemoteFileId { get; set; }
        public string? BaselineJson { get; set; }
        public DateTimeOffset? LastSuccessfulSync { get; set; }
    }

    public async Task<SyncState> LoadAsync()
    {
        if (!File.Exists(_path)) return new SyncState();
        await using var stream = File.OpenRead(_path);
        return await JsonSerializer.DeserializeAsync<SyncState>(stream, Options)
            ?? new SyncState();
    }

    public async Task SaveAsync(SyncState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temp = _path + ".tmp";
        await using (var stream = File.Create(temp))
            await JsonSerializer.SerializeAsync(stream, state, Options);
        File.Move(temp, _path, true);
    }

    public Task ClearAsync()
    {
        if (File.Exists(_path)) File.Delete(_path);
        return Task.CompletedTask;
    }
}
