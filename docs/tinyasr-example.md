# Worked example: tinyasr v2.1 (ARM64-only app)

[**tinyasr**](https://github.com/ampl0/tinyasr) is an offline, on-device speech-recognition app (RNN-T engine written in Rust, exposed via JNI). It is a great second test case because it is **arm64-v8a only** — the single native library is `lib/arm64-v8a/libtinyasr_jni.so`.

## Result: launches and renders ✅

Under PenguDroid 0.3.0, `dev.tinyasr.app` (full APK, v2.1) launched successfully:

- The **Berberis ARM64 translator** initialized and JIT-translated aarch64 code:
  ```
  art: LoadNativeBridge(libberberis_arm64.so) = true
  art: PreInitializeNativeBridge(/tmp/pengudroid-bridge, arm64) = true
  [pengudroid:log] INFO berberis: Initialized Berberis (aarch64)
  ```
- The app installed its bundled model packs (en/es/fa/km/uz `.tzf`) and `silero_vad.onnx`.
- The main UI rendered (verified by OCR):
  ```
  tinyasr
  Offline speech recognition, on device
  MODELS  en.tzf (34.2 MB)  Language: en
  IMPORT MODELS
  TRANSCRIBE | AUDIO RECORD LIVE | FILE
  ... VAD ok
  Your transcript will appear here.
  ```
- Screenshots: `docs/screenshots/tinyasr-*.png`.

## What could NOT be exercised here

The app's **Live** (mic streaming), **Record**, and **Transcribe** features need:

1. **A microphone / audio capture** — not present in the headless container, and PenguDroid does not (yet) implement audio *recording*.
2. **Interactive input** to press the buttons — not available headless (see `docs/limitations.md`).

So end-to-end transcription could not be run in this environment, but the app, its ARM64 engine, and its UI all verified as working.

## Takeaways

- **ARM64-only apps can run** when the runtime's native bridge (Berberis) is present — this is a meaningful capability beyond the pure-Java case.
- To actually exercise features that need mic/input, use a real interactive host with a microphone.
