using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Daybook.Windows.Models;

namespace Daybook.Windows.Services;

public sealed class GoogleDriveApi
{
    private const string DriveApi = "https://www.googleapis.com/drive/v3";
    private const string UploadApi = "https://www.googleapis.com/upload/drive/v3";
    private const string FolderMimeType = "application/vnd.google-apps.folder";
    private const string DataFileName = "daybook-data.json";
    private readonly HttpClient _http = new();

    public sealed record DriveFile(string Id, string Name, long Version, string? ETag);

    public async Task<string> GetOrCreateDaybookFolderAsync(string accessToken, CancellationToken cancellationToken = default)
    {
        var query = "name = 'Daybook' and mimeType = '" + FolderMimeType + "' and trashed = false";
        using var response = await SendAsync(
            HttpMethod.Get,
            $"{DriveApi}/files?q={Uri.EscapeDataString(query)}&spaces=drive&fields=files(id,name)&pageSize=100",
            accessToken,
            cancellationToken);

        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        var files = json.RootElement.GetProperty("files");
        if (files.GetArrayLength() > 0)
            return files[0].GetProperty("id").GetString()!;

        var metadata = JsonSerializer.Serialize(new { name = "Daybook", mimeType = FolderMimeType });
        using var create = await SendAsync(
            HttpMethod.Post,
            $"{DriveApi}/files?fields=id,name",
            accessToken,
            cancellationToken,
            new StringContent(metadata, Encoding.UTF8, "application/json"));

        using var created = await JsonDocument.ParseAsync(await create.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        return created.RootElement.GetProperty("id").GetString()
            ?? throw new InvalidOperationException("Google Drive не вернул ID папки Daybook.");
    }

    public async Task<DriveFile?> FindDataFileAsync(
        string folderId, string accessToken, CancellationToken cancellationToken = default)
    {
        var query = $"'{folderId}' in parents and name = '{DataFileName}' and trashed = false";
        using var response = await SendAsync(
            HttpMethod.Get,
            $"{DriveApi}/files?q={Uri.EscapeDataString(query)}&spaces=drive&fields=files(id,name,version)&pageSize=100",
            accessToken,
            cancellationToken);

        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        var files = json.RootElement.GetProperty("files");
        if (files.GetArrayLength() == 0) return null;

        var first = files[0];
        var id = first.GetProperty("id").GetString()!;
        var version = first.TryGetProperty("version", out var versionValue) ? versionValue.GetInt64() : 0;

        using var metadataResponse = await SendAsync(
            HttpMethod.Get,
            $"{DriveApi}/files/{Uri.EscapeDataString(id)}?fields=id,name,version",
            accessToken,
            cancellationToken);
        using var metadata = await JsonDocument.ParseAsync(
            await metadataResponse.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        if (metadata.RootElement.TryGetProperty("version", out var latestVersion))
            version = latestVersion.GetInt64();

        return new DriveFile(id, DataFileName, version, metadataResponse.Headers.ETag?.Tag);
    }

    public async Task<DaybookDocument> DownloadDocumentAsync(
        DriveFile file, string accessToken, CancellationToken cancellationToken = default)
    {
        using var response = await SendAsync(
            HttpMethod.Get,
            $"{DriveApi}/files/{Uri.EscapeDataString(file.Id)}?alt=media",
            accessToken,
            cancellationToken);
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        return await JsonSerializer.DeserializeAsync<DaybookDocument>(stream, cancellationToken: cancellationToken)
            ?? throw new InvalidOperationException("Файл daybook-data.json пуст или имеет неверный формат.");
    }

    public async Task<DriveFile> UploadDocumentAsync(
        string? fileId,
        string folderId,
        DaybookDocument document,
        string accessToken,
        string? expectedETag = null,
        CancellationToken cancellationToken = default)
    {
        var boundary = "daybook_" + Guid.NewGuid().ToString("N");
        var metadata = fileId is null
            ? JsonSerializer.Serialize(new { name = DataFileName, mimeType = "application/json", parents = new[] { folderId } })
            : JsonSerializer.Serialize(new { name = DataFileName, mimeType = "application/json" });
        var payload = JsonSerializer.Serialize(document);
        var body = "--" + boundary + "\r\n" +
                   "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                   metadata + "\r\n" +
                   "--" + boundary + "\r\n" +
                   "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                   payload + "\r\n" +
                   "--" + boundary + "--";

        var method = fileId is null ? HttpMethod.Post : HttpMethod.Patch;
        var url = fileId is null
            ? $"{UploadApi}/files?uploadType=multipart&fields=id,name,version"
            : $"{UploadApi}/files/{Uri.EscapeDataString(fileId)}?uploadType=multipart&fields=id,name,version";

        using var request = new HttpRequestMessage(method, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        if (!string.IsNullOrWhiteSpace(expectedETag))
            request.Headers.TryAddWithoutValidation("If-Match", expectedETag);
        request.Content = new StringContent(body, Encoding.UTF8);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("multipart/related");
        request.Content.Headers.ContentType.Parameters.Add(new NameValueHeaderValue("boundary", boundary));

        using var response = await _http.SendAsync(request, cancellationToken);
        await EnsureSuccessAsync(response, cancellationToken);
        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        var id = json.RootElement.GetProperty("id").GetString()!;
        var version = json.RootElement.TryGetProperty("version", out var v) ? v.GetInt64() : 0;
        return new DriveFile(id, DataFileName, version, response.Headers.ETag?.Tag);
    }

    private async Task<HttpResponseMessage> SendAsync(
        HttpMethod method,
        string url,
        string accessToken,
        CancellationToken cancellationToken,
        HttpContent? content = null)
    {
        using var request = new HttpRequestMessage(method, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        request.Content = content;
        var response = await _http.SendAsync(request, cancellationToken);
        try
        {
            await EnsureSuccessAsync(response, cancellationToken);
            return response;
        }
        catch
        {
            response.Dispose();
            throw;
        }
    }

    private static async Task EnsureSuccessAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode) return;
        var detail = await response.Content.ReadAsStringAsync(cancellationToken);
        if (detail.Length > 500) detail = detail[..500];
        throw new GoogleDriveException((int)response.StatusCode, detail);
    }
}

public sealed class GoogleDriveException(int statusCode, string detail)
    : Exception($"Google Drive HTTP {statusCode}: {detail}")
{
    public int StatusCode { get; } = statusCode;
}
