# tinyasr verifier — test your own recordings, on any host

A small host-side tool that runs the **same `asr engine** used by the tinyasr Android app against **any audio file you record**, so you can verify:

1. whether the engine actually produces text for your audio, and
2. **which language** matches (the usual cause of an empty "Your transcript will appear here.").

It has two modes, mirroring the app's two paths:

- `--mode file` — the whole-file transcription pass (like the app's `FILE`).
- `--mode stream` — the live/streaming pass (like the app's `LIVE`), pushing 100 ms chunks.

## Build

The engine is built from the tinyasr repo (`third_party/tinyasr`), then linked with the small C harness:

```bash
# in the tinyasr repo
cargo build --release --features c-api          # -> target/release/libtinyasr.a

cc scripts/tinyasr-verify.c target/release/libtinyasr.a \
   -lpthread -ldl -lm -o tinyasr-verify
```

Dependencies: `gcc`, `cargo` (Rust), `python3`, and `ffmpeg` (for mp3/m4a/ogg/flac — WAV works without it).

## Get the models

```bash
curl -LO https://github.com/ampl0/tinyasr/releases/download/v2.1/en.tzf
curl -LO https://github.com/ampl0/tinyasr/releases/download/v2.1/silero_vad.onnx
```

## Use

```bash
# Probe every language in the pack (fast diagnosis):
./tinyasr-verify.sh my_recording.mp3 auto

# Stream / live path, pinned to English:
./tinyasr-verify.sh my_recording.wav en --mode stream

# Whole-file pass, pinned to English:
./tinyasr-verify.sh my_recording.wav en --mode file
```

`MODEL`/`VAD` env vars override the default `en.tzf` / `silero_vad.onnx` in the current directory.

## Example output

```
engine v6  chunk=320ms  audio=4.74s  langs(pack): en ru uk
  lang en  => The load is in a test on the offline transcription and in running on a phone.
  lang ru  => (empty — no text)
  lang uk  => (empty — no text)
VERDICT: TEXT PRODUCED (engine works; in the app, pin to a language that matches)
```

## What it tells you

- If `auto` finds text in some language → the engine works; the empty transcript in the app is a **language mismatch** (pick that pack+language).
- If **all** languages are empty on a long, voiced recording → suspect the audio itself (very quiet, no speech, wrong sample content). Use a clear full sentence ≥ 2 s.
