#!/usr/bin/env bash
# Launch any Android APK in PenguDroid with the working WebView flags.
# Usage: bash scripts/launch_app.sh /opt/apks/app.apk
set -euo pipefail

APK="${1:?usage: launch_app.sh <app.apk>}"
APPIMAGE="${APPIMAGE:-/opt/PenguDroid-0.3.0-x86_64.AppImage}"

export HOME="${HOME:-/home/$USER}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg-pengu}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

# WebView provider flags (requires the provider installed, see docs/webview-provider.md)
export PENGUDROID_WEBVIEW=1
export PENGUDROID_WEBVIEW_SINGLE_PROCESS=1
export PENGUDROID_WEBVIEW_SOFTWARE=1
export PENGUDROID_FRAMEWORK_LOOPER=1

# Optional: dump rendered frames for screenshots
if [[ -n "${DUMP_FRAMES:-}" ]]; then
  mkdir -p "$DUMP_FRAMES"
  export PENGUDROID_DUMP_FRAME="$DUMP_FRAMES/frame.ppm"
  export PENGUDROID_DUMP_FRAME_EVERY="${DUMP_EVERY:-3}"
fi

echo "Launching $APK via $APPIMAGE"
nohup "$APPIMAGE" --cli launch "$APK" > /tmp/launch.log 2>&1 &
echo "pid=$!  logs=/tmp/launch.log"
echo "tail -f /tmp/launch.log"
