<div align="center">

# 🐧 PenguDroid on Colab / Headless — Field Guide

**Run ` Android apps (APK) as native Linux processes — no Android VM, no container, no root — and capture real screenshots from a headless server like Google Colab.**

Built and verified against **PenguDroid 0.3.0** + **YTCache 1.2.0** (the *app used as the test harness for this guide).

![Platform](https://img.shields.io/badge/platform-Linux%20x86__64-3DDC84) ![Runtime](https://img.shields.io/badge/Android_17-API_37-1082c8) ![AppImage](https://img.shields.io/badge/AppImage-0.3.0-blue) ![License](https://img.shields.io/badge/license-MIT-green) ![AI-ready](https://img.shields.io/badge/AI_agent-guide-purple)

</div>

---

## Table of contents

- [What this is](#what-this-is)
- [Why PenguDroid](#why-pengudroid)
- [Repository layout](#repository-layout)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [The WebView blocker (important)](#the-webview-blocker)
- [Headless + screenshots](#headless--screenshots)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [For AI agents](#for-ai-agents)
- [Keywords](#keywords)
- [License](#license)

---

## What this is

This repository is a **reproducible, hand-verified guide** for running modern Android apps on a server-grade, **headless** Linux box (e.g. a Google Colab notebook or a low-spec container) using [**PenguDroid**](https://github.com/denizomer10/PenguDroid).

It documents:

- How to install PenguDroid (AppImage → self-contained Android 17 / API-37 runtime).
- How to install an APK the "correct" way (read-only, non-root).
- How to fix the **WebView provider** — the single most common blocker, since shipped runtimes omit the Android System WebView.
- How to produce **real screenshots from a headless host** (no GPU, no display) using PenguDroid's built-in framebuffer dump.
- What actually works and what does **not** work without a GPU (so future agents don't waste time).

The example target app is **YTCache** (`com.ytcache.app`, v1.2.0) — an Android app that plays YouTube in a WebView and caches media segments for offline playback. It is a good stress test because it requires a working WebView.

---

## Why PenguDroid

Unlike an Android emulator, PenguDroid runs each app as an **ordinary Linux process** on Android's real ART runtime and framework — no full OS boot, no KVM, no nested virtualization.

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
    ├── launch_ytcache.sh  # launch ARTCache with the working WebView flags
    └── capture_frames.sh  # grab screenshots via framebuffer dump
```

---

## Requirements

- **Linux x86_64** (tested on Ubuntu 24.04).
- A **non-root** user account (PenguDroid refuses to run apps as root).
- ~5 GB free disk for the runtime (origin ~2.3 GB).
- A **Wayland** compositor. On a desktop this is your normal session; on Colab we run a headless `sway`.
- GPU drivers are **recommended** to show windows; **software rendering (llvmpipe)** is enough to run and screenshot (details below).

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

# 4. Install your APK read-only
sudo mkdir -p /opt/apks && sudo cp app.apk /opt/apks/ && sudo chmod 444 /opt/apks/app.apk

# 5. Launch
PENGUDROID_WEBVIEW=1 \
PENGUDROID_WEBVIEW_SINGLE_PROCESS=1 \
PENGUDROID_WEBVIEW_SOFTWARE=1 \
PENGUDROID_FRAMEWORK_LOOPER=1 \
./PenguDroid-0.3.0-x86_64.AppImage --cli launch /opt/apks/app.apk
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

## The WebView blocker

**Symptom:** the app launches but crashes on `android.webkit.WebView` with:

```
java.lang.UnsupportedOperationException
    at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:351)
```

**Cause:** the shipped PenguDroid runtime **omits the Android System WebView APK** (`com.android.webview`) and the provider loading contract — but many modern apps (YTCache, and others) need it.

**Fix:** extract the *real* `com.android.webview` from Google's official API-37 system image and place it into the runtime tree. See **docs/webview-provider.md** for the exact, fully-tested procedure (it uses `lpunpack` + `debugfs` on the official `system-images;android-37.0;default;x86_64` image).

---

## Headless + screenshots

On a GPU-less host the app's window cannot be *presented* to a compositor, but PenguDroid can **dump rendered frames** to disk as PPM → PNG:

```bash
PENGUDROID_DUMP_FRAME=/tmp/frames/frame.ppm \
PENGUDROID_DUMP_FRAME_EVERY=3 \
./PenguDroid-0.3.0-x86_64.AppImage --cli launch /opt/apks/app.apk
# → /tmp/frames/frame.s0frameN.ppm   (720x1280 RGBA readback)
```

See **scripts/capture_frames.sh** and **docs/environment.md**.

---

## Known limitations

| Limitation | Evidence / detail |
|---|---|
| No sandbox | Apps run as your user; trusted APKs only |
| WebView not shipped | Must install provider (see docs) |
| Input injection on headless | `tap`/`key` are `Ack`'d but **not delivered** — the daemon needs a compositor input focus, which headless has no pointer for |
| No GPU ⇒ no visible window | `eglSwapBuffers` fails dmabuf export; still screenshots via frame dump |
| YouTube consent gate | The WebView loads the real site, so Google's cookie-consent page appears first |
| Capture requires live playback | YTCache only records media segments *while* a video plays (MSE tee); idle app produces no capture files |

The full list with verification notes is in **docs/limitations.md**.

---

## Troubleshooting

| Error | Fix |
|---|---|
| `Writable dex file … is not allowed` | make the APK read-only: `chmod 444` |
| `process-identity: … host UID is root` | run as a non-root user |
| `Failed to open APK '/system/product/app/webview/webview.apk'` | follow **docs/webview-provider.md** for the symlinks |
| `webview: framework rejected provider APK` | wrong/unsigned WebView APK; re-extract |

More in **docs/limitations.md** and **AGENTS.md**.

---

## For AI agents

A dedicated, self-contained reproduction guide lives in **AGENTS.md** — written so an LLM coding agent (or a future visitor) can reproduce the whole environment from a bare container, including acceptance checks.

---

## Keywords

`pengudroid`, `android`, `apk`, `android-on-linux`, `run android apps on linux`, `colab`, `google colab`, `headless`, `headless android`, `android emulator alternative`, `emulator`, `appimage`, `art`, `android 17`, `api 37`, `webview`, `chromium`, `com.android.webview`, `lpunpack`, `system image`, `software rendering`, `llvmpipe`, `sway`, `wayland`, `screenshots`, `framebuffer`, `ytcache`, `youtube cache`, `mse`, `media source extensions`, `offline video`, `automation`, `ai agent`, `ci`, `testing`, `gui testing`, `android testing`

---

## License

MIT — see `LICENSE`. PenguDroid is Apache-2.0; Android/Chromium components keep their own licenses. YTCache is MIT. All respective marks belong to their owners; this repo is independent and not affiliated with Google, PenguDroid, or YTCache.
