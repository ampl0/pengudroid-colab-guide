#!/usr/bin/env bash
# tinyasr-verify.sh <audio> [langs|auto] [--mode stream|file]
# Decode any recording and verify it against the engine, diagnosing an empty
# transcript (language mismatch vs. dead engine).
#
# Examples:
#   tinyasr-verify.sh my_recording.mp3 auto          # probe every pack language (file mode)
#   tinyasr-verify.sh my_recording.wav en --mode stream   # run the live/streaming path
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODE="file"
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE="$2"; shift 2 ;;
        --langs) LANGS="$2"; shift 2 ;;
        *) POSITIONAL+=("$1"); shift ;;
    esac
done

AUDIO="${POSITIONAL[0]:?usage: tinyasr-verify.sh <audio> [langs] [--mode stream|file]}"
LANGS="${LANGS:-${POSITIONAL[1]:-auto}}"

MODEL="${MODEL:-$HERE/en.tzf}"
VAD="${VAD:-$HERE/silero_vad.onnx}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 "$HERE/convert_audio.py" "$AUDIO" "$TMP/in.f32" >/dev/null

"$HERE/tinyasr-verify" "$MODEL" "$VAD" "$TMP/in.f32" --mode "$MODE" --langs "$LANGS"
