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

`build.sh` also runs the plain-JVM unit tests in `test/` (no Android APIs; a failure
fails the build).

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
GET  /status                 app/webview/memory state, lifetime counters, last_unclean_exit, mem_history
GET  /logs?since=SEQ&n=500   app log (ring buffer)
GET  /logs/file?kb=256       persisted log incl. previous runs
GET  /logcat?n=500           this process's logcat
GET  /screenshot?scale=0.5   JPEG of the window
GET  /discover?secs=6        browse mDNS for hubs
POST /eval /reload /navigate?url= /hub?url=    need the X-Debug-Token header
```

## Wake-word listening

If the hub has the `voice` container app, the display streams its mic to it nonstop for wake-word
detection on the hub (`ListenSession`). Every 5 min it asks `GET <hub>/display/api/kiosk/` for
`apps.voice` (the app's `http://host:port` origin) and connects a WebSocket to
`<origin>/voice/api/listen?display=<ip>&v=<version>`. It sends 20 ms frames of 16 kHz s16le mono
audio and gets JSON text frames back:

- `hello`: the wake words and the detection threshold.
- `wake`: chime, a blue border flash, and a `bdh-wake` DOM event on the hub page.
- `config` with `listen:false`: disconnect, then retry in 5 min.

Without a voice app (no `voice` key) the display doesn't listen. A dropped connection retries with
a 2 s to 60 s backoff. Listening stops while the activity is paused, and also during doorbell
talk-back, because Android 9 allows only one `AudioRecord`. A small green dot in the bottom-right
corner shows the mic is streaming. The mic source is `VOICE_COMMUNICATION` (on Connect displays
`VOICE_RECOGNITION` delivers only zeros); after 3 s of exact digital silence it falls back to
`MIC`, then `VOICE_RECOGNITION`. `/status` → `listen` shows state, source, level, failures and the
last wake.

## Crash diagnostics

Android 9 has no ApplicationExitInfo, and a native/GPU crash kills the process without running
Java code. So every 30 s the app rewrites `files/run.marker` (pid, boot id, version, memory, page
stats) and deletes it in `onDestroy`. A marker found at startup means the previous run ended
uncleanly. It is classified as `reboots`, `upgrades`, `bg_kills` or `process_deaths`. For
`process_deaths`, the dead pid's logcat (including `Fatal signal` lines) is recovered into the log
as `prev-logcat`. `/status` shows `lifetime` counters (persisted across runs), `last_unclean_exit`,
and `mem_history` (the last 20 samples, about 10 min). The history is also logged on a renderer
crash or hang and under memory pressure.