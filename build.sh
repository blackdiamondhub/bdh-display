#!/bin/bash
# Builds out/bdh-display.apk without Gradle. Runs inside eclipse-temurin:17-jdk
# (locally via docker, in CI as the job container). Env:
#   ANDROID_SDK          SDK dir (default /opt/sdk); build-tools/platform installed if missing
#   VERSION_CODE_OVERRIDE / VERSION_NAME_OVERRIDE   override version.properties
#   DEBUG_TOKEN_SHA256   sha256 hex of the debug API token (control endpoints off if empty)
#   KEYSTORE             signing keystore (default debug.jks; storepass/keypass android, alias key)
#   KEYSTORE_B64         base64 keystore, written to $KEYSTORE if set
#   REQUIRE_KEYSTORE=1   fail instead of generating a throwaway key (release builds:
#                        Connect only upgrades an app signed with the same key)
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/opt/sdk}
BTV=34.0.0
. ./version.properties
VERSION_CODE=${VERSION_CODE_OVERRIDE:-$VERSION_CODE}
VERSION_NAME=${VERSION_NAME_OVERRIDE:-$VERSION_NAME}
KEYSTORE=${KEYSTORE:-debug.jks}
export DEBIAN_FRONTEND=noninteractive
need() { command -v "$1" >/dev/null || { apt-get update -qq && apt-get install -y -qq unzip zip curl >/dev/null; }; }
need unzip; need zip; need curl
if [ ! -x "$SDK/build-tools/$BTV/aapt2" ] || [ ! -f "$SDK/platforms/android-28/android.jar" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -sSLo /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o /tmp/clt.zip -d "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  # `yes` dies of SIGPIPE when sdkmanager exits; that must not trip pipefail.
  (set +o pipefail; yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "build-tools;$BTV" "platforms;android-28" >/dev/null)
  [ -x "$SDK/build-tools/$BTV/aapt2" ] && [ -f "$SDK/platforms/android-28/android.jar" ] || { echo "ERROR: SDK install failed" >&2; exit 1; }
fi
BT=$SDK/build-tools/$BTV
AJ=$SDK/platforms/android-28/android.jar
rm -rf out && mkdir -p out/classes out/dex out/gen/com/blackdiamondlodge/display
cat > out/gen/com/blackdiamondlodge/display/BuildInfo.java <<EOF
package com.blackdiamondlodge.display;
final class BuildInfo { static final String DEBUG_TOKEN_SHA256 = "${DEBUG_TOKEN_SHA256:-}"; }
EOF
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml out/res.zip \
  --min-sdk-version 28 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
# Unit tests for the pure-Java classes (plain JVM; no Android APIs). Failure fails the build.
rm -rf out/test && mkdir -p out/test
javac -nowarn -d out/test src/com/blackdiamondlodge/display/RunMarker.java src/com/blackdiamondlodge/display/TimeZoneFix.java $(find test -name '*.java')
java -cp out/test com.blackdiamondlodge.display.RunMarkerTest
java -cp out/test com.blackdiamondlodge.display.TimeZoneFixTest
javac -nowarn --release 8 -classpath "$AJ" -d out/classes $(find src out/gen -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
if [ -n "${KEYSTORE_B64:-}" ]; then echo "$KEYSTORE_B64" | base64 -d > "$KEYSTORE"; fi
if [ ! -f "$KEYSTORE" ]; then
  if [ "${REQUIRE_KEYSTORE:-0}" = 1 ]; then echo "ERROR: $KEYSTORE missing and REQUIRE_KEYSTORE=1" >&2; exit 1; fi
  echo "WARNING: no keystore, signing with a throwaway key (Connect can't upgrade from this)" >&2
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=throwaway >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out out/bdh-display.apk out/aligned.apk
"$BT/apksigner" verify --print-certs out/bdh-display.apk > out/certs.txt && grep -m1 'SHA-256' out/certs.txt
"$BT/aapt2" dump badging out/bdh-display.apk > out/badging.txt && head -1 out/badging.txt
ls -l out/bdh-display.apk