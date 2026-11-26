#!/usr/bin/env bash
# Headless Wayland (sway) + software GL setup for PenguDroid.
# Usage: bash scripts/setup_headless.sh   (as a non-root user)
set -euo pipefail

# ---- config ----
XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg-pengu}"
WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
MODE="${MODE:-900x1600}"

echo "[1/4] Preparing XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"
install -d -m 700 "$XDG_RUNTIME_DIR"

echo "[2/4] Writing sway config"
cat > /tmp/sway.conf <<EOF
output * bg #1b1b1f solid_color
default_border none
default_floating_border none
font pango:DejaVu Sans 10
EOF

echo "[3/4] Starting headless sway"
WLR_BACKENDS=headless \
WLR_LIBINPUT_NO_DEVICES=1 \
WLR_RENDERER=pixman \
  sway -c /tmp/sway.conf > /tmp/sway.log 2>&1 &
sleep 5
export XDG_RUNTIME_DIR WAYLAND_DISPLAY

echo "[4/4] Setting display mode $MODE"
wlr-randr --output HEADLESS-1 --custom-mode "$MODE" || true

echo "Wayland socket ready: $XDG_RUNTIME_DIR/$WAYLAND_DISPLAY"
echo "Software GL hint: export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe"
