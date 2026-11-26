#!/usr/bin/env bash
# Build the tinyasr verifier APK (arm64).
# Usage:
#   bash scripts/build_tinyasr_verifier.sh
# Output: examples/tinyasr-verifier-app/app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$HERE/examples/tinyasr-verifier-app"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE="${GRADLE:-/opt/gradle/gradle-8.7/bin/gradle}"

if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
  echo "Install first: sdkmanager --install \"platforms;android-34\" \"build-tools;34.0.0\" \"ndk;26.1.10909125\""
  exit 1
fi
if [ ! -x "$GRADLE" ]; then
  echo "Gradle 8.7 not found at $GRADLE (set GRADLE=...)."
  exit 1
fi

cd "$APP"
"$GRADLE" assembleDebug --no-daemon --console=plain
echo
echo "APK: $APP/app/build/outputs/apk/debug/app-debug.apk"
