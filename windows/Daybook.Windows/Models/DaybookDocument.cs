using System.Text.Json.Serialization;

namespace Daybook.Windows.Models;

public sealed class DaybookDocument
{
    [JsonPropertyName("tasks")]
    public List<DaybookEntry> Tasks { get; set; } = [];
    [JsonPropertyName("notes")]
    public List<DaybookEntry> Notes { get; set; } = [];
}

public sealed class DaybookEntry
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();
    [JsonPropertyName("title")]
    public string Title { get; set; } = "";
    [JsonPropertyName("body")]
    public string Body { get; set; } = "";
    [JsonPropertyName("created")]
    public long Created { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    [JsonPropertyName("updated")]
    public long Updated { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    [JsonPropertyName("due")]
    public string? Due { get; set; }
    [JsonPropertyName("priority")]
    public string? Priority { get; set; } = "normal";
    [JsonPropertyName("done")]
    public bool Done { get; set; }
}
