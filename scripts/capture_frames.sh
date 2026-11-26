#!/usr/bin/env bash
# Convert PenguDroid framebuffer dumps (PPM) to PNG + OCR the latest frame.
# Usage: bash scripts/capture_frames.sh [dir]
set -euo pipefail

DIR="${1:-/tmp/frames}"
LATEST=$(ls -t "$DIR"/frame.*.ppm 2>/dev/null | head -1)
[[ -n "$LATEST" ]] || { echo "no frames in $DIR"; exit 1; }

echo "latest: $LATEST"
OUT="${LATEST%.ppm}.png"
convert "$LATEST" "$OUT"
echo "png: $OUT"

if command -v tesseract >/dev/null 2>&1; then
  echo "---- OCR ----"
  tesseract "$OUT" stdout 2>/dev/null | grep -vE '^\s*$'
fi
