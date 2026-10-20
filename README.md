# App Hub for Android

One app that installs and updates all my Android apps from their GitHub releases.
It is the Android companion of [App Hub](https://github.com/Hafij-DEU/Application-hub) for Ubuntu.

| App | Repository |
|---|---|
| Astra Player | Hafij-DEU/astra-player-android |
| DocScan | Hafij-DEU/Scan |
| ScreenLingo | Hafij-DEU/live-translate |
| Markd | Hafij-BGE/markd |
| WorkSuite | Hafij-BGE/work-suite (pre-releases) |
| App Hub itself | Hafij-DEU/app-hub-android |

## How updates stay quiet

App Hub installs through a `PackageInstaller` session, the same way ReVanced Manager,
F-Droid and Obtainium do. On Android 12+ each session asks for
`USER_ACTION_NOT_REQUIRED`, and the app holds `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.
Android then installs an update with no dialog when all of these hold:

- App Hub is the app's installer of record (App Hub installed it, or updated it once).
- The app targets a recent SDK (all of these target 34 or 35).
- The new APK is signed with the same key as the installed app.

The first install of each app, or the first update of an app installed some other way
(file manager, adb), shows one system dialog. After that, updates are silent.

Google Play Protect is separate. It may warn about any APK it has not seen before.
No code setting turns that off; see "Play Protect" below.

## Features

- Update all in one tap; App Hub updates itself last.
- Picks the APK for the device ABI (arm64-v8a, armeabi-v7a, x86_64, x86), else a universal APK.
- Verifies downloads against the release's `SHA256SUMS` when one is published.
- Background check every 6 hours with a notification. Optional silent auto-install on Wi-Fi.
- Add any other GitHub repo with APK releases (menu ▸ Add app).
- The app list also loads from `catalog.json` in this repo, so new apps show up without an App Hub release.
- ETag caching, so checks rarely count against GitHub's 60/hour limit. An optional token lifts it.

## Adding an app to the catalog

Add an entry to `catalog.json` (repo root, read by installed copies) and to
`app/src/main/assets/catalog.json` (the bundled fallback):

```json
{ "id": "myapp", "name": "My App", "repo": "owner/repo", "package": "com.example.myapp" }
```

`"prerelease": true` makes App Hub also consider GitHub pre-releases for that app.

## Play Protect

Play Protect warnings ("Unsafe app blocked", "Install anyway") depend on the APK's
reputation, not on how it is installed. To reduce them:

- Keep the targetSdk current and never change signing keys.
- Submit each release APK to Google for review through the Play Protect appeals form
  for developers.
- Register the signing keys in the Android Developer Console (Android developer verification).

## Build

Plain Java, no AndroidX, no dependencies. Builds offline.

```bash
source ~/.android/apphub-release.env
~/.local/opt/gradle-8.11.1/bin/gradle --offline assembleRelease
```

The release key lives outside the repo. Every App Hub release must use the same key,
or installed copies cannot update themselves.
