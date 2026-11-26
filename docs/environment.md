# Environment: Headless / Colab setup

This documents the exact headless environment used to verify this guide (Ubuntu 24.04 container, no GPU, no KVM).

---

## 1. Rationale

PenguDroid normally wants a desktop Wayland session with a GPU. On a server/Colab we substitute:

- **Compositor:** `sway` running with the `headless` backend + `pixman` software renderer.
- **GPU:** llvmpipe (`GALLIUM_DRIVER=llvmpipe`), so the guest's EGL/GLES work entirely in software.

This is enough to **run** apps and **screenshot** them. It is *not* enough to *present* a visible window or to deliver scripted input.

---

## 2. Headless Wayland

```bash
# as the non-root user
export XDG_RUNTIME_DIR=/tmp/xdg-pengu
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

cat > /tmp/sway.conf <<EOF
output * bg #1b1b1f solid_color
default_border none
default_floating_border none
font pango:DejaVu Sans 10
EOF

WLR_BACKENDS=headless \
WLR_LIBINPUT_NO_DEVICES=1 \
WLR_RENDERER=pixman \
  sway -c /tmp/sway.conf > /tmp/sway.log 2>&1 &

sleep 5
export WAYLAND_DISPLAY=wayland-1
wlr-randr --output HEADLESS-1 --custom-mode 900x1600   # pick a portrait phone-ish size
```

Notes:

- `WLR_RENDERER=pixman` is required (the default `gles2` needs a DRM fd, which does not exist here).
- `--custom-mode 900x1600` gives a portrait canvas; the app itself reports a logical 720×1280.
- Each user needs their **own** `XDG_RUNTIME_DIR`.

---

## 3. Software GL

```bash
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
```

---

## 4. Dependencies (verified)

```bash
apt-get install -y \
  sway grim wlr-randr \
  libegl1 libgles2 mesa-utils \
  imagemagick        # PPM -> PNG
  tesseract-ocr      # optional, to read screenshots
  curl unzip git python3 openjdk-17-jdk
```

For the WebView provider you additionally need `lpunpack` (python) and `debugfs` (from `e2fsprogs`).

---

## 5. Frame capture

PenguDroid never sees a usable window in this setup (presentation fails), but its GL shim can read back the rendered framebuffer:

```bash
export PENGUDROID_DUMP_FRAME=/tmp/frames/frame.ppm
export PENGUDROID_DUMP_FRAME_EVERY=3    # write every 3rd frame
# relaunch the app; files appear as frame.s0frameN.ppm
```

Convert and read:
```bash
convert frame.s0frame24.ppm shot.png
tesseract shot.png stdout
```

The PPM is 720×1280 RGBA readback with the RGB rows in top-down order.

---

## 6. What does NOT work here

- **Visible windows:** requires a GPU dmabuf path → absent.
- **Scripted input (`tap`/`key`):** requires a compositor input focus → absent in headless.
- **Hardware video decode:** requires host GPU/VA-API → absent; software decode is used.
