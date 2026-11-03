# Daybook native sync design

## Remote document

The native clients use the same Drive location as the web client:

- Folder: `Daybook` in the Drive root
- File: `daybook-data.json`
- OAuth scope: `https://www.googleapis.com/auth/drive.file`

The JSON document remains compatible with the web app:

```json
{
  "tasks": [
    {
      "id": "stable-id",
      "title": "Example task",
      "body": "",
      "created": 1790000000000,
      "updated": 1790000000000,
      "due": "2026-11-10",
      "priority": "normal",
      "done": false
    }
  ],
  "notes": []
}
```

## Conflict safety

Each device stores the last common JSON snapshot separately from its current local data. Sync compares that baseline with the local and remote snapshots.

- If only one side changed an entry, that change is kept.
- Independent additions and edits to different entries are merged.
- If both sides changed the same entry differently, sync reports a conflict and does not upload either version over the other.
- A deletion is treated as a change. Delete-versus-edit is a conflict, not an automatic resurrection or silent data loss.
- The baseline is advanced only after a successful merge/upload or a successful download.

The merge implementation exists in both native projects:
- Android: `DaybookSyncMerger`
- Windows: `DaybookSyncMerger`

Android and Windows also have separate local stores for the last common snapshot and Drive file identity.

## OAuth configuration required before live sync

The native applications must use installed-app OAuth flows; they must not reuse the web app's browser token model.

1. Keep the existing Google Drive API enabled and the existing web OAuth client for the PWA.
2. Create a **Desktop app** OAuth client for Windows. The Windows client will use Authorization Code + PKCE with a loopback redirect. No client secret is embedded in the executable.
3. Create an **Android** OAuth client for package `ru.daybook.app`, using the SHA-1 fingerprint of the Android signing certificate that will sign the distributed APK.
4. Keep the requested Drive scope limited to `drive.file`.
5. Windows refresh tokens must be protected with Windows DPAPI. Android should use Google Play services authorization and request a fresh access token when required; do not persist a raw access token.

Do not distribute a release APK until the Android release signing key and matching OAuth Android client are configured. A CI debug APK is useful for early installation tests, but its signing certificate is not a stable release identity.
