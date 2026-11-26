# tinyasr verifier app (example)

A minimal Android app built for this guide that lets you verify the tinyasr
engine **on a phone**:

- pick a `.tzf` model pack, pick an audio file,
- tap **Verify** — it runs the engine against the audio in **every** language
  the pack contains and prints which languages produce text (same diagnostic as
  `docs/tinyasr-verifier.md`, but on-device).

It bundles `silero_vad.onnx` and the prebuilt arm64 `libtinyasr_jni.so`
(from the tinyasr repo's prebuilt engine).

## Build

```bash
bash scripts/build_tinyasr_verifier.sh
# -> examples/tinyasr-verifier-app/app/build/outputs/apk/debug/app-debug.apk
```

Notes:
- Requires Android SDK (platform 34, build-tools 34.0.0) + Gradle 8.7.
- `ABI = arm64-v8a` only (matches phones and PenguDroid's Berberis path).
- `useLegacyPackaging = true` so the `.so` is extracted (required by PenguDroid).

## Use on a phone

1. Install the APK.
2. **PICK MODEL (.TZF)** → choose e.g. `en.tzf`.
3. **PICK AUDIO** → choose a recording.
4. **VERIFY (AUTO LANGUAGES)** → see per-language results + verdict.

If every language is empty on a long recorded voiced clip, the audio itself is
the problem (too quiet / not speech). If one language matches but the app's
Live shows nothing, that's the language-mismatch case in the main app.

## Auto-run (for headless/CI testing)

If `model.tzf` and `audio.bin` already exist in the app's cache dir
(`.../data/dev.verify.asr/cache/`), the app verifies automatically at launch —
used to test under PenguDroid without UI taps.
