# Daybook native rewrite

This branch develops native Android and Windows clients while keeping the existing GitHub Pages web app unchanged.

## Targets

- Android: Kotlin, Jetpack Compose, Room, WorkManager.
- Windows: C# / .NET 10 WPF, SQLite, Windows DPAPI-protected OAuth token storage.
- Shared cloud data: Google Drive folder `Daybook`, file `daybook-data.json`.
- Shared JSON contract: preserve existing task/note fields (`id`, `title`, `body`, `created`, `updated`, plus task fields `due`, `priority`, `done`) and add a schema version only in a backward-compatible way.

## OAuth design

### Android
Use Google Play services `AuthorizationClient` for Drive authorization. On app launch, call `authorize()` for the previously granted scopes. If the grant still exists, Google Play services can return a usable access token without asking the user to consent again; if not, launch the returned PendingIntent. Do not attempt to embed a client secret or persist a bearer access token.

Android OAuth client registration requires the final application package name and signing certificate SHA-1. Register debug and release signing fingerprints as appropriate.

### Windows
Use a Google OAuth client of type **Desktop app** and the installed-app authorization flow with PKCE and the system browser / loopback redirect. The Windows app is a public client: no client secret is shipped in the executable. Store the refresh token using Windows DPAPI (CurrentUser) and refresh access tokens through Google's token endpoint / supported .NET OAuth library.

## Drive permissions

Request the narrow `drive.file` scope. Keep the folder and data file created by Daybook. If the native clients cannot see the existing web-created folder/file under this per-file grant, provide a one-time Google Picker selection/migration path rather than requesting broad access to the user's entire Drive.

## Data safety and synchronization

- Never overwrite the existing Drive file during first-run migration without an explicit comparison and backup.
- Import the current JSON shape without dropping fields.
- Use local-first storage on each platform.
- Add conflict-aware synchronization; never silently replace concurrent edits.
- Preserve deletions with tombstones in the new schema so deleted records do not reappear on another device.
- Keep the web app and its current deployment untouched until native clients pass migration and cross-device tests.

## Delivery stages

1. Native solution skeletons and shared data contract.
2. Android UI and local persistence.
3. Windows UI and local persistence.
4. OAuth on each platform.
5. Drive sync and safe migration from the existing JSON file.
6. Automated builds, APK and Windows installer.
7. Cross-device tests and release notes.

## Important OAuth distinction

Android's Authorization API is not a manually managed refresh-token flow: it reuses the user's existing grant and obtains access tokens through Google Play services. Windows uses the desktop installed-app OAuth flow and protects its long-lived refresh token with DPAPI. Both can provide a one-time consent experience without a separate server, subject to Google revoking or expiring the grant.
