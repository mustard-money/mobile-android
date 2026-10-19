#!/usr/bin/env bash
# Builds the native Android wrapper around the Mustard PWA (a web view with document scanning)
# and runs it. Finds the Android SDK and a JDK on its own, so no global setup is needed.
#
#   ./run.sh               debug build, installed and opened on the running emulator or
#                          the connected device (boots the first emulator if there's none)
#   ./run.sh --url URL     same, opening URL instead, e.g. the website's dev server:
#                          ./run.sh --url http://localhost:5173/  (adb reverse is set up for you)
#   ./run.sh build         release APK + AAB in app/build/outputs/
#
# PWA_URL=https://… builds with another deployment than the one in gradle.properties.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
PACKAGE=com.mustard.mobile

step() { printf '\n\033[1;33m▸ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✖ %s\033[0m\n' "$*" >&2; exit 1; }

COMMAND=run
START_URL=
while [ $# -gt 0 ]; do
  case "$1" in
    build) COMMAND=build ;;
    run) ;;
    --url) START_URL="${2:?--url needs a URL}"; shift ;;
    -h | --help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "Unknown argument: $1 (see ./run.sh --help)" ;;
  esac
  shift
done

# ---------- Toolchain ----------

for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  if [ -n "$sdk" ] && [ -d "$sdk/platform-tools" ]; then export ANDROID_HOME="$sdk"; break; fi
done
[ -d "${ANDROID_HOME:-}/platform-tools" ] || fail "Android SDK not found. Install Android Studio or set ANDROID_HOME."
ADB="$ANDROID_HOME/platform-tools/adb"

# The Android Gradle plugin needs JDK 17 or newer.
java_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
default_java="$(command -v java || true)"
for jdk in "${JAVA_HOME:-}" "${default_java%/bin/java}" "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
  if [ -x "$jdk/bin/java" ] && [ "$(java_major "$jdk")" -ge 17 ] 2>/dev/null; then export JAVA_HOME="$jdk"; break; fi
  unset JAVA_HOME
done
[ -n "${JAVA_HOME:-}" ] || fail "No JDK 17 or newer found. Install one or set JAVA_HOME."

GRADLE=(./gradlew --console=plain -q)
[ -n "${PWA_URL:-}" ] && GRADLE+=("-PpwaUrl=$PWA_URL")
PWA_URL="${PWA_URL:-$(sed -n 's/^pwaUrl=//p' gradle.properties)}"

# ---------- Commands ----------

if [ "$COMMAND" = build ]; then
  step "Building the release APK and AAB for $PWA_URL"
  "${GRADLE[@]}" :app:assembleRelease :app:bundleRelease
  echo "APK (install on emulators/devices): app/build/outputs/apk/release/app-release.apk"
  echo "AAB (Google Play upload):           app/build/outputs/bundle/release/app-release.aab"
  [ -f keystore.properties ] || echo "Signed with the debug key; add keystore.properties before publishing (see README.md)."
  exit 0
fi

step "Building the debug APK for ${START_URL:-$PWA_URL}"
"${GRADLE[@]}" :app:assembleDebug

if ! "$ADB" devices | grep -qE '\t(device|offline)$'; then
  avd="$("$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | grep -v '^INFO' | head -1 || true)"
  [ -n "$avd" ] || fail "No device connected and no emulator set up. Create one in Android Studio's Device Manager."
  step "Booting the $avd emulator"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$avd" >/dev/null 2>&1 &
fi
"$ADB" wait-for-device
# Booted, and past the placeholder home screen Android shows first: an app opened before
# the real launcher arrives ends up underneath it.
resumed() { "$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity || true; }
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] &&
  [ -n "$(resumed)" ] && [[ "$(resumed)" != *FallbackHome* ]]; do
  sleep 1
done

step "Installing and opening the app"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
extras=()
if [ -n "$START_URL" ]; then
  # A dev server on this machine's localhost becomes the device's localhost too.
  port="$(printf '%s' "$START_URL" | sed -nE 's#^http://localhost:([0-9]+).*#\1#p')"
  [ -n "$port" ] && "$ADB" reverse "tcp:$port" "tcp:$port" >/dev/null
  extras=(--es url "$START_URL")
fi
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -n "$PACKAGE/.MainActivity" ${extras[@]+"${extras[@]}"} >/dev/null
