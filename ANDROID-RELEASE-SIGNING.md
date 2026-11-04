# Android release signing

The Android release APK must always be signed with the same private keystore. Losing or replacing that keystore prevents installing future updates over an existing release build.

## 1. Create a permanent keystore on Windows

Run this in PowerShell in a private folder on your PC:

```powershell
keytool -genkeypair -v -keystore daybook-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias daybook
```

If `keytool` is not found, use the JDK bundled with Android Studio, usually:

```powershell
& "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore daybook-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias daybook
```

Choose a strong keystore password and key password. Keep the keystore and both passwords in a safe, backed-up place. Do not commit the keystore to Git.

## 2. Get the Android OAuth SHA-1 fingerprint

```powershell
keytool -list -v -keystore daybook-release.jks -alias daybook
```

Copy the `SHA1` fingerprint from the output. In Google Cloud Console, create/configure an OAuth client of type **Android** with:

- Package name: `ru.daybook.app`
- SHA-1 certificate fingerprint: the value from the command above

The OAuth consent screen and Google Drive API must remain configured in the same Google Cloud project as the existing app.

## 3. Add GitHub Actions secrets

Convert the keystore to one Base64 line:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("daybook-release.jks")) | Set-Content -NoNewline daybook-keystore-base64.txt
```

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name | Value |
|---|---|
| `DAYBOOK_KEYSTORE_BASE64` | Entire contents of `daybook-keystore-base64.txt` |
| `DAYBOOK_KEYSTORE_PASSWORD` | Keystore password |
| `DAYBOOK_KEY_ALIAS` | `daybook` |
| `DAYBOOK_KEY_PASSWORD` | Key password |

Do not paste the keystore or passwords into chat, issues, commits, or workflow logs. Delete the temporary Base64 text file after adding the secret, while retaining a secure backup of the original keystore.

## 4. Build

Every push to `native-rewrite` continues to produce a debug APK. Once all four secrets exist, CI additionally produces `daybook-android-release`, a signed release APK. The same keystore is used for future releases.

The release APK is not produced until the repository secrets have been configured. A debug APK and a release APK have different signing certificates and Android will not install one over the other as an update.
