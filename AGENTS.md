# AGENTS.md — Reproduction Guide for AI Agents

> This file is written for an **LLM coding agent** (or any future engineer) to reproduce a working **PenguDroid → APK → WebView → screenshots** environment on a bare **headless Linux container (e.g. Google Colab)** from scratch. It was verified end-to-end in exactly this environment.

---

## 0. Acceptance criteria

You are done when **all** of these hold:

1. `pengudroid --cli --version` prints `0.3.0`.
2. `pengudroid runtime --status` reports an installed **Android 17 / API-37** modular runtime.
3. An APK of your choice (example used in this guide: `com.ytcache.app`) **launches** and stays alive (`ps` shows the process).
4. If the app uses a WebView, it shows real site content — verified by reading the rendered frame (OCR or pixel analysis). In the worked example this is Google's consent page, i.e. `https://m.youtube.com` is reached.
5. Screenshots are produced as PNG from `PENGUDROID_DUMP_FRAME` PPM output.

You do **not** need a GPU or a visible window to pass; software rendering + frame dump is enough.

---

## 1. Environment + (Colab)

```bash
# Debian/Ubuntu container
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  curl unzip git python3 openjdk-17-jdk \
  sway grim wlr-randr mesa-utils libegl1 libgles2 \
  imagemagick tesseract-ocr
```

Verify:
```bash
uname -m            # must be x86_64
nproc               # >=2
free -g             # >=4GB recommended
ls /dev/kvm         # NOT required for PenguDroid
ls /dev/dri         # usually absent on Colab -> fine, use software GL
```

---

## 2. Create a non-root user

PenguDroid refuses to run apps as root (`process-identity: host UID is root`).

```bash
useradd -m -s /bin/bash pengu
```

Run everything below as `pengu` (use `sudo -u pengu` or `su pengu -c '…'`).

---

## 3. Install PenguDroid

```bash
cd /opt
curl -LO https://github.com/denizomer10/PenguDroid/releases/download/v0.3.0/PenguDroid-0.3.0-x86_64.AppImage
curl -LO https://github.com/denizomer10/PenguDroid/releases/download/v0.3.0/SHA256SUMS
sha256sum -c SHA256SUMS     # verify
chmod +x PenguDroid-0.3.0-x86_64.AppImage

# First run prepares the runtime (one time, ~2.3 GB into ~/.cache/pengudroid)
sudo -u pengu /opt/PenguDroid-0.3.0-x86_64.AppImage --cli --version
sudo -u pengu /opt/PenguDroid-0.3.0-x86_64.AppImage --cli runtime --status
```

Runtime location (noted for later):
```bash
~pengu/.cache/pengudroid/runtime/android17-x86_64-modular-versions/<hash>/
```

---

## 4. Headless Wayland (sway) + software GL

```bash
sudo -u pengu bash -c '
export XDG_RUNTIME_DIR=/tmp/xdg-pengu
mkdir -p $XDG_RUNTIME_DIR && chmod 700 $XDG_RUNTIME_DIR
cat > /tmp/sway.conf <<EOF
output * bg #1b1b1f solid_color
default_border none
default_floating_border none
font pango:DejaVu Sans 10
EOF
WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman \
  sway -c /tmp/sway.conf &
sleep 5
WLR_RENDERER=pixman; export XDG_RUNTIME_DIR WAYLAND_DISPLAY=wayland-1
wlr-randr --output HEADLESS-1 --custom-mode 900x1600
'
```

Software GL for the guest:
```bash
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
```

---

## 5. Install the WebView provider (critical)

Many apps (including `com.ytcache.app`) require the Android System WebView. The shipped runtime omits it. Full instructions: **docs/webview-provider.md**.

TL;DR (paths relative to runtime `<R>` = `~pengu/.cache/pengudroid/runtime/android17-x86_64-modular-versions/<hash>`):

```bash
# Extract com.android.webview/webview.apk from Google API-37 x86_64 system image
# (system-images;android-37.0;default;x86_64) via lpunpack + debugfs.
R=$(ls -d ~pengu/.cache/pengudroid/runtime/android17-x86_64-modular-versions/*/ | head -1)

mkdir -p "$R/components/platform/product/app/webview"
install -m 444 webview.apk "$R/components/platform/product/app/webview/webview.apk"

# Host-side /system symlinks so the Android process can open them
ln -sfn "$R/components/platform/product" /system/product
ln -sfn "$R/components/native"            /system/lib64
```

Verify:
```bash
sudo -u pengu /opt/PenguDroid-0.3.0-x86_64.AppImage --cli info webview.apk
# → package: name='com.android.webview' versionName='145.0.7632.218'
```

---

## 6. Install your APK read-only

```bash
sudo mkdir -p /opt/apks
sudo install -m 444 app.apk /opt/apks/app.apk
```

> The APK **must** be read-only, or ART fails with
> `Writable dex file '...' is not allowed.`

---

## 7. Launch your app with WebView flags

```bash
sudo -u pengu bash -c '
export HOME=/home/pengu
export XDG_RUNTIME_DIR=/tmp/xdg-pengu WAYLAND_DISPLAY=wayland-1
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
export PENGUDROID_WEBVIEW=1
export PENGUDROID_WEBVIEW_SINGLE_PROCESS=1
export PENGUDROID_WEBVIEW_SOFTWARE=1
export PENGUDROID_FRAMEWORK_LOOPER=1
cd /opt
nohup ./PenguDroid-0.3.0-x86_64.AppImage --cli launch /opt/apks/app.apk \
  > /tmp/launch.log 2>&1 &
'
# Wait ~90–150s for preparation + WebView init
tail -f /tmp/launch.log
```

Expected log milestones:
```
webview: framework verified com.android.webview 145.0.7632.218, 1 signer(s)
webview: verified provider package, loader and update service installed
webview: software Canvas layer selected
```

---

## 8. Screenshots (no GPU)

```bash
export PENGUDROID_DUMP_FRAME=/tmp/frames/frame.ppm
export PENGUDROID_DUMP_FRAME_EVERY=3       # every 3rd presented frame
# relaunch as above
ls /tmp/frames/                              # frame.s0frameN.ppm  (720x1280)
convert /tmp/frames/frame.s0frame24.ppm shot.png
tesseract shot.png stdout                     # read the screen
```

`PENGUDROID_TRACE_PRESENTS=1` prints a per-second present counter — useful to confirm the surface is rendering even when invisible.

---

## 9. Pitfalls (learned the hard way)

1. **Root is rejected.** Always run as a non-root user.
2. **Writable APK is rejected.** `chmod 444` the APK.
3. **WebView missing** → app crashes in `WebViewFactory.getProvider`. Install the provider (step 5) *and* set all four `PENGUDROID_WEBVIEW*` flags.
4. **No input injection headless.** `pengudroid tap/key` return `Ack` but the daemon never logs `subscribed to input` — the compositor has no pointer focus. Do not rely on scripted clicks in a headless container.
5. **No GPU ⇒ no visible window**, but `PENGUDROID_DUMP_FRAME` still yields real rendered frames.
6. **YouTube consent gate.** The WebView reaches the real site; Google's consent page appears before content. Treat any "still on consent" frame as *expected*, not a failure.
7. **Capture is app-specific.** YTCache (the example) writes capture files only *while a video plays*; an idle app yields no `YTCache/<videoId>/` output. To observe those data files you must get past consent + play a video (requires a real, interactive host). For your own app, decide what "working" means and verify that specifically.

---

## 10. When to escalate

If any acceptance criterion fails, consult in order:
1. `docs/webview-provider.md` (WebView issues)
2. `docs/environment.md` (headless/Wayland issues)
3. `docs/limitations.md` (expected limitations)
4. Upstream: https://github.com/denizomer10/PenguDroid (`docs/STATUS.md`, `docs/LIMITATIONS.md`)
