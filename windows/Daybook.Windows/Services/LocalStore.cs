using System.Text.Json;
using Daybook.Windows.Models;

namespace Daybook.Windows.Services;

public sealed class LocalStore
{
    private readonly string _path = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Daybook", "daybook-data.json");
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public async Task<DaybookDocument> LoadAsync()
    {
        if (!File.Exists(_path)) return new DaybookDocument();
        await using var stream = File.OpenRead(_path);
        return await JsonSerializer.DeserializeAsync<DaybookDocument>(stream, Options)
            ?? new DaybookDocument();
    }

    public async Task SaveAsync(DaybookDocument document)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temp = _path + ".tmp";
        await using (var stream = File.Create(temp))
            await JsonSerializer.SerializeAsync(stream, document, Options);
        File.Move(temp, _path, true);
    }
}
