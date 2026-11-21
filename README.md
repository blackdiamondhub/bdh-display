# BDH Display

Android kiosk app for UniFi Connect displays (`com.blackdiamondlodge.display`, Android 9 / API 28).
It shows the BlackDiamondHub UI full-screen in a WebView, finds the hub over mDNS (`_bdh-hub._tcp`),
bridges doorbell talk-back (`TalkSession`), recovers from renderer crashes and hangs, and serves a
debug HTTP API on port 8787.

## Build

CI (`.github/workflows/build.yml`) builds every PR and push. A push to `main` creates GitHub
release `v<VERSION_NAME>` with the APK attached, but only if that tag doesn't exist yet. To ship a
change, bump `VERSION_CODE` and `VERSION_NAME` in `version.properties`. PR CI fails if app files
changed and the version is already released.

Local build (no Gradle; the SDK is cached in a docker volume):

```
docker run --rm -v android-sdk:/opt/sdk -v "$PWD":/src -e DEBUG_TOKEN_SHA256=<hex> \
  eclipse-temurin:17-jdk bash /src/build.sh      # -> out/bdh-display.apk
```

Signing: Connect only upgrades an installed app if the new APK is signed with the same key. That
key is stored in the `KEYSTORE_B64` repo secret, and release builds fail without it. Never commit
`*.jks`. Repo secrets:

- `KEYSTORE_B64`: base64 of the keystore (alias `key`, passwords `android`).
- `DEBUG_TOKEN_SHA256`: SHA-256 hex of the debug token. Without it, the POST control endpoints
  are disabled.

## Install

1. Download the APK from the release.
2. In the UniFi Connect UI, upload it under **Manage Apps**.
3. Launch the app on the display (it must be in **app** mode, not web mode).

## Debug API (port 8787, private networks only)

```
GET  /status                 app/webview/memory state
GET  /logs?since=SEQ&n=500   app log (ring buffer)
GET  /logs/file?kb=256       persisted log incl. previous runs
GET  /logcat?n=500           this process's logcat
GET  /screenshot?scale=0.5   JPEG of the window
GET  /discover?secs=6        browse mDNS for hubs
POST /eval /reload /navigate?url= /hub?url=    need the X-Debug-Token header
```
