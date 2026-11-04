# Daybook Native

Native rewrite workspace for Android (Kotlin/Jetpack Compose) and Windows (C#/.NET WPF).

## Project layout

- `android/` — Android application.
- `windows/` — Windows desktop application.
- `shared/` — platform-neutral data contract and migration notes.
- Existing web app files remain on the repository's main branch and are not modified by this rewrite.

## Data compatibility

Both clients use the same JSON document shape as the existing web app:

```json
{
  "tasks": [],
  "notes": []
}
```

Existing task and note properties are preserved during migration. Local-first storage is the source of truth while editing; Drive synchronization must detect conflicts rather than silently overwrite newer data.

## Build prerequisites

- Android: Android Studio with JDK 17+ and Android SDK 36.
- Windows: Visual Studio 2026 or .NET 10 SDK with the Windows Desktop workload.

OAuth client secrets must never be committed to this repository. Android and Windows use separate public-client OAuth flows and platform secure storage; no embedded client secret or refresh token.
