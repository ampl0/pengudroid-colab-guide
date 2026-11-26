<div align="center">

# 🐧 PenguDroid on Colab / Headless — Run Any Android App

**Run nearly any Android app (APK) as a native Linux process — no Android VM, no container, no root, no KVM — and capture real screenshots from a headless server like Google Colab.**

A generic, reproducible field guide. Verified against **PenguDroid 0.3.0**, with **YTCache 1.2.0** as one worked example (a WebView app that exercises the whole pipeline).

![Platform](https://img.shields.io/badge/platform-Linux%20x86__64-3DDC84) ![Runtime](https://img.shields.io/badge/Android_17-API_37-1082c8) ![License](https://img.shields.io/badge/license-MIT-green) ![AI-ready](https://img.shields.io/badge/AI_agent-guide-purple)

</div>

---

## Table of contents

- [What this is](#what-this-is)
- [Why PenguDroid](#why-pengudroid)
- [Repository layout](#repository-layout)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Testing any APK](#testing-any-apk)
- [The WebView blocker (important)](#the-webview-blocker)
- [Headless + screenshots](#headless--screenshots)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [For AI agents](#for-ai-agents)
- [Keywords](#keywords)
- [License](#license)

---

## What this is

This repository is a **reproducible, hand-verified guide** for running modern Android apps on a server-grade, **headless** Linux box (e.g. a Google Colab notebook or a container) using [**PenguDroid**](https://github.com/denizomer10/PenguDroid).

It covers:

- Installing PenguDroid (AppImage → self-contained Android 17 / API-37 runtime).
- Installing **any APK** the "correct" way (read-only, non-root).
- Fixing the **WebView provider** — the single most common blocker for "web" apps, since shipped runtimes omit the Android System WebView.
- Producing **real screenshots from a headless host** (no GPU, no display).
- How to run, inspect, and validate **whichever app you bring**.

The method is app-agnostic: swap in your APK. YTCache is documented as a real, working example.

---

## Why PenguDroid

PenguDroid runs each app as an **ordinary Linux process** on Android's real ART runtime and framework — no full OS boot, no KVM, no nested virtualization.

| Aspect | Android Emulator | PenguDroid |
|---|---|---|
| Hardware acceleration | Needs KVM (rare on Colab) | Runs on CPU + software GL |
| Boot time | Minutes | Seconds (one process) |
| Requires Android boot | Yes (full system image) | No OS boot |
| Sandbox | Built-in | **None by default** (run only trusted APKs) |
| Screenshots headless | Hard | Built-in framebuffer dump |

> ⚠️ **Security:** PenguDroid runs apps with your *user's* access — there is **no sandbox**. Only run APKs you trust.

---

## Repository layout

```
.
├── README.md              # this file
├── AGENTS.md              # step-by-step guide + prompts for AI agents
├── docs/
│   ├── environment.md     # headless/Colab environment setup
│   ├── webview-provider.md# how to extract & install Android System WebView
│   └── limitations.md     # known hard limits and evidence
└── scripts/
    ├── setup_headless.sh  # headless Wayland (sway) + software GL
    ├── launch_app.sh       # launch ANY APK with the working WebView flags
    └── capture_frames.sh  # grab screenshots via framebuffer dump
```

---

## Requirements

- **Linux x86_64** (tested on Ubuntu 24.04).
- A **non-root** user account (PenguDroid refuses to run apps as root).
- ~5 GB free disk for the runtime (origin ~2.3 GB).
- A **Wayland** compositor. On a desktop this is your normal session; on Colab we run a headless `sway`.
- GPU drivers are **recommended** to show windows; **software rendering (llvmpipe)** is enough to run and screenshot.

---

## Quick start (TL;DR)

```bash
# 1. Download PenguDroid AppImage
curl -LO https://github.com/denizomer10/PenguDroid/releases/download/v0.3.0/PenguDroid-0.3.0-x86_64.AppImage
chmod +x PenguDroid-0.3.0-x86_64.AppImage

# 2. Launch a headless Wayland session (non-root user)
sudo -u "$USER" bash scripts/setup_headless.sh

# 3. (Required for WebView apps) install the Android System WebView provider
#     see docs/webview-provider.md

# 4. Install your APK read-only — any app will work
sudo mkdir -p /opt/apks && sudo cp your-app.apk /opt/apks/ && sudo chmod 444 /opt/apks/your-app.apk

# 5. Launch
PENGUDROID_WEBVIEW=1 \
PENGUDROID_WEBVIEW_SINGLE_PROCESS=1 \
PENGUDROID_WEBVIEW_SOFTWARE=1 \
PENGUDROID_FRAMEWORK_LOOPER=1 \
./PenguDroid-0.3.0-x86_64.AppImage --cli launch /opt/apks/your-app.apk
```

Full, verbose instructions: **docs/environment.md** and **docs/webview-provider.md**.

---

## How it works

```
APK ──► Android ART (real AOSP runtime/framework)
           │
           ├─► WebView (Chromium) ──► host network
           ├─► EGL/GLES ──► host GPU (or llvmpipe software)
           ├─► PipeWire audio
           └─► Binder/Window broker (the "daemon")
```

PenguDroid's `pengudroid` CLI:
- `launch app.apk` — start the app (ART backend).
- `runtime --status` — show the prepared Android runtime.
- `info app.apk` — dump manifest.
- `doctor app.apk` — check what the app needs.
- `ps` / `stop` — manage running apps.
- `tap X Y` / `key CODE` — inject input (needs a compositor input focus, see Limitations).

---

## Testing any APK

The whole method is generic. To bring your own app:

1. **Inspect it first** — this tells you what it needs (ABIs, SDK, permissions, services):
   ```bash
   ./PenguDroid-0.3.0-x86_64.AppImage --cli info my-app.apk
   ./PenguDroid-0.3.0-x86_64.AppImage --cli doctor my-app.apk
   ```
2. **Install it read-only** and launch with `scripts/launch_app.sh` (add `DUMP_FRAMES=/tmp/frames` to screenshot it).
3. **Common per-app needs** (add as needed):
   - **Pure Java/Kotlin apps** → usually "just work".
   - **WebView apps** (YouTube wrappers, WebViews, many games' login) → need the WebView provider (step 3 / `docs/webview-provider.md`).
   - **ARM64-only native libs** → need PenguDroid's native-bridge/translator (mostly not verified here; check upstream).
   - **Google Play / GMS-dependent apps** → generally unsupported (no Play services).
4. **Validate** — confirm the process stays alive, then read the rendered frames (OCR/pixels) to confirm the UI reached the expected screen.

Example we verified end-to-end: **YTCache 1.2.0** (`com.ytcache.app`) — launches, initializes Chromium, and loads `https://m.youtube.com`.

---

## The WebView blocker

**Symptom:** the app crashes on `android.webkit.WebView` with:

```
java.lang.UnsupportedOperationException
    at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:351)
```

**Cause:** the shipped PenguDroid runtime **omits the Android System WebView APK** (`com.android.webview`) and the provider loading contract — but many modern apps need it.

**Fix:** extract the *real* `com.android.webview` from Google's official API-37 system image and place it into the runtime tree. See **docs/webview-provider.md** (uses `lpunpack` + `debugfs` on `system-images;android-37.0;default;x86_64`).

---

## Headless + screenshots

On a GPU-less host the app's window cannot be *presented* to a compositor, but PenguDroid can **dump rendered frames** to disk as PPM → PNG:

```bash
PENGUDROID_DUMP_FRAME=/tmp/frames/frame.ppm \
PENGUDROID_DUMP_FRAME_EVERY=3 \
./PenguDroid-0.3.0-x86_64.AppImage --cli launch /opt/apks/your-app.apk
# → /tmp/frames/frame.s0frameN.ppm   (720x1280 RGBA readback)
```

See **scripts/capture_frames.sh** and **docs/environment.md**.

---

## Known limitations

| Limitation | Detail |
|---|---|
| No sandbox | Apps run as your user; trusted APKs only |
| WebView not shipped | Must install provider (see docs) |
| Input injection on headless | `tap`/`key` are `Ack`'d but **not delivered** — needs a compositor input focus |
| No GPU ⇒ no visible window | `eglSwapBuffers` fails dmabuf export; still screenshots via frame dump |
| Google Play / GMS | Not available |
| ARM64 native libs | Translator is unverified in this guide |
| Site consent gates | WebView-based apps will show the real site (e.g. Google consent) before content |

Full list with evidence: **docs/limitations.md**.

---

## Troubleshooting

| Error | Fix |
|---|---|
| `Writable dex file … is not allowed` | make the APK read-only: `chmod 444` |
| `process-identity: … host UID is root` | run as a non-root user |
| `Failed to open APK '/system/product/app/webview/webview.apk'` | follow **docs/webview-provider.md** (symlinks) |
| `webview: framework rejected provider APK` | wrong/unsigned WebView APK; re-extract |

More in **docs/limitations.md** and **AGENTS.md**.

---

## For AI agents

A dedicated, self-contained reproduction guide lives in **AGENTS.md** — written so an LLM coding agent (or a future visitor) can reproduce the whole environment from a bare container for **any app**, including acceptance checks.

---

## Keywords

`pengudroid`, `android`, `apk`, `android-on-linux`, `run android apps on linux`, `colab`, `google colab`, `headless`, `headless android`, `android emulator alternative`, `emulator`, `appimage`, `art`, `android 17`, `api 37`, `webview`, `chromium`, `com.android.webview`, `lpunpack`, `system image`, `software rendering`, `llvmpipe`, `sway`, `wayland`, `screenshots`, `framebuffer`, `ytcache`, `youtube cache`, `mse`, `media source extensions`, `offline video`, `automation`, `ai agent`, `ci`, `testing`, `gui testing`, `android testing`, `apk testing`, `arbitrary apps`

---

## License

MIT — see `LICENSE`. PenguDroid is Apache-2.0; Android/Chromium components keep their own licenses. YTCache is MIT. All respective marks belong to their owners; this repo is independent and not affiliated with Google, PenguDroid, or YTCache.
