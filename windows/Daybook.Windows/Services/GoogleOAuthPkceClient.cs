using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Daybook.Windows.Services;

/// <summary>
/// Google installed-app OAuth using Authorization Code + PKCE and a loopback
/// redirect. Refresh tokens are protected for the current Windows user by DPAPI.
/// </summary>
public sealed class GoogleOAuthPkceClient
{
    private const string Scope = "https://www.googleapis.com/auth/drive.file";
    private readonly HttpClient _http = new();
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Daybook");
    private string ClientIdPath => Path.Combine(_directory, "google-oauth-client-id.txt");
    private string RefreshTokenPath => Path.Combine(_directory, "google-refresh-token.dpapi");

    public async Task ConfigureClientIdAsync(string clientId)
    {
        if (string.IsNullOrWhiteSpace(clientId) || !clientId.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal))
            throw new ArgumentException("Укажи корректный Google OAuth Client ID.", nameof(clientId));

        Directory.CreateDirectory(_directory);
        await File.WriteAllTextAsync(ClientIdPath, clientId.Trim());
    }

    public async Task<bool> IsConfiguredAsync()
    {
        var clientId = await ReadClientIdAsync();
        return !string.IsNullOrWhiteSpace(clientId);
    }

    public Task<string?> GetConfiguredClientIdAsync() => ReadClientIdAsync();

    public async Task<string> GetAccessTokenAsync(bool forceInteractive = false, bool allowInteractive = true, CancellationToken cancellationToken = default)
    {
        var clientId = await ReadClientIdAsync();
        if (string.IsNullOrWhiteSpace(clientId))
            throw new InvalidOperationException(
                "Сначала добавь Desktop OAuth Client ID в настройках Daybook.");

        if (!forceInteractive)
        {
            var savedRefreshToken = await ReadRefreshTokenAsync();
            if (!string.IsNullOrWhiteSpace(savedRefreshToken))
            {
                try
                {
                    return await RefreshAccessTokenAsync(clientId, savedRefreshToken, cancellationToken);
                }
                catch (GoogleOAuthException ex) when (ex.ErrorCode is "invalid_grant" or "unauthorized_client")
                {
                    // The user revoked access or the saved grant is no longer valid.
                    if (File.Exists(RefreshTokenPath)) File.Delete(RefreshTokenPath);
                    if (!allowInteractive)
                        throw new GoogleOAuthException("reauthorization_required", "Требуется повторное подключение Google Drive.");
                }
            }
        }

        if (!allowInteractive)
            throw new GoogleOAuthException("reauthorization_required", "Требуется повторное подключение Google Drive.");
        return await AuthorizeInteractivelyAsync(clientId, cancellationToken);
    }

    public Task<bool> HasSavedRefreshTokenAsync() => Task.FromResult(File.Exists(RefreshTokenPath));

    public Task DisconnectAsync()
    {
        if (File.Exists(RefreshTokenPath)) File.Delete(RefreshTokenPath);
        return Task.CompletedTask;
    }

    private async Task<string?> ReadClientIdAsync()
    {
        if (!File.Exists(ClientIdPath)) return null;
        var value = (await File.ReadAllTextAsync(ClientIdPath)).Trim();
        return value.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal) ? value : null;
    }

    private async Task<string?> ReadRefreshTokenAsync()
    {
        if (!File.Exists(RefreshTokenPath)) return null;
        try
        {
            var protectedBytes = Convert.FromBase64String(await File.ReadAllTextAsync(RefreshTokenPath));
            var plainBytes = ProtectedData.Unprotect(protectedBytes, optionalEntropy: null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plainBytes);
        }
        catch
        {
            return null;
        }
    }

    private async Task SaveRefreshTokenAsync(string refreshToken)
    {
        Directory.CreateDirectory(_directory);
        var protectedBytes = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(refreshToken), optionalEntropy: null, DataProtectionScope.CurrentUser);
        await File.WriteAllTextAsync(RefreshTokenPath, Convert.ToBase64String(protectedBytes));
    }

    private async Task<string> RefreshAccessTokenAsync(
        string clientId, string refreshToken, CancellationToken cancellationToken)
    {
        using var response = await _http.PostAsync(
            "https://oauth2.googleapis.com/token",
            new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = clientId,
                ["refresh_token"] = refreshToken,
                ["grant_type"] = "refresh_token"
            }),
            cancellationToken);

        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw ParseOAuthError(json.RootElement);

        return json.RootElement.GetProperty("access_token").GetString()
            ?? throw new GoogleOAuthException("missing_access_token", "Google не вернул access token.");
    }

    private async Task<string> AuthorizeInteractivelyAsync(string clientId, CancellationToken cancellationToken)
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var redirectUri = $"http://127.0.0.1:{port}/";

        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var state = Base64Url(RandomNumberGenerator.GetBytes(24));

        var query = new Dictionary<string, string>
        {
            ["client_id"] = clientId,
            ["redirect_uri"] = redirectUri,
            ["response_type"] = "code",
            ["scope"] = Scope,
            ["access_type"] = "offline",
            ["prompt"] = "consent",
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
            ["state"] = state
        };
        var authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            string.Join("&", query.Select(pair =>
                Uri.EscapeDataString(pair.Key) + "=" + Uri.EscapeDataString(pair.Value)));

        var acceptTask = listener.AcceptTcpClientAsync(cancellationToken).AsTask();
        Process.Start(new ProcessStartInfo(authorizationUrl) { UseShellExecute = true });

        using var client = await acceptTask.WaitAsync(TimeSpan.FromMinutes(4), cancellationToken);
        var stream = client.GetStream();
        using var reader = new StreamReader(stream, Encoding.ASCII, leaveOpen: true);
        var requestLine = await reader.ReadLineAsync(cancellationToken) ?? "";
        string? line;
        do { line = await reader.ReadLineAsync(cancellationToken); } while (!string.IsNullOrEmpty(line));

        var target = requestLine.Split(' ').ElementAtOrDefault(1) ?? "/";
        var callback = new Uri(new Uri(redirectUri), target);
        var callbackQuery = ParseQuery(callback.Query);
        var html = "<html><body><h2>Daybook: авторизация завершена</h2><p>Можно закрыть это окно и вернуться в приложение.</p></body></html>";
        var bytes = Encoding.UTF8.GetBytes(html);
        var header = Encoding.ASCII.GetBytes(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " +
            bytes.Length + "\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(header, cancellationToken);
        await stream.WriteAsync(bytes, cancellationToken);
        await stream.FlushAsync(cancellationToken);

        if (!callbackQuery.TryGetValue("state", out var returnedState) ||
            !CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(returnedState), Encoding.UTF8.GetBytes(state)))
            throw new GoogleOAuthException("invalid_state", "Проверка OAuth state не пройдена.");

        if (callbackQuery.TryGetValue("error", out var error))
            throw new GoogleOAuthException(error, "Google отклонил авторизацию.");

        if (!callbackQuery.TryGetValue("code", out var code))
            throw new GoogleOAuthException("missing_code", "Google не вернул authorization code.");

        using var tokenResponse = await _http.PostAsync(
            "https://oauth2.googleapis.com/token",
            new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = clientId,
                ["code"] = code,
                ["code_verifier"] = verifier,
                ["redirect_uri"] = redirectUri,
                ["grant_type"] = "authorization_code"
            }),
            cancellationToken);

        using var tokenJson = await JsonDocument.ParseAsync(
            await tokenResponse.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);

        if (!tokenResponse.IsSuccessStatusCode)
            throw ParseOAuthError(tokenJson.RootElement);

        if (tokenJson.RootElement.TryGetProperty("refresh_token", out var refresh) &&
            !string.IsNullOrWhiteSpace(refresh.GetString()))
            await SaveRefreshTokenAsync(refresh.GetString()!);

        return tokenJson.RootElement.GetProperty("access_token").GetString()
            ?? throw new GoogleOAuthException("missing_access_token", "Google не вернул access token.");
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        return query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .Where(parts => parts.Length == 2)
            .ToDictionary(
                parts => Uri.UnescapeDataString(parts[0].Replace("+", " ")),
                parts => Uri.UnescapeDataString(parts[1].Replace("+", " ")),
                StringComparer.Ordinal);
    }

    private static string Base64Url(byte[] value) =>
        Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static GoogleOAuthException ParseOAuthError(JsonElement root)
    {
        var code = root.TryGetProperty("error", out var error) ? error.GetString() ?? "oauth_error" : "oauth_error";
        var description = root.TryGetProperty("error_description", out var detail)
            ? detail.GetString() ?? "Ошибка Google OAuth."
            : "Ошибка Google OAuth.";
        return new GoogleOAuthException(code, description);
    }
}

public sealed class GoogleOAuthException(string errorCode, string message) : Exception(message)
{
    public string ErrorCode { get; } = errorCode;
}
