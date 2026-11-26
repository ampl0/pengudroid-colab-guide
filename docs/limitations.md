# Known limitations (verified)

Everything below was observed directly during the verification run of this guide on a headless Ubuntu 24.04 container.

---

## 1. No sandbox

PenguDroid runs * app with your host user's access. There is **no per-app sandbox** by default. The project itself warns against running untrusted APKs.

**Impact:** only install APKs you trust.

---

## 2. WebView is not shipped

The Android 17 / API-37 modular runtime **omits** `com.android.webview` and the provider-loading contract. Apps that construct a `WebView` crash unless you install the provider (see `docs/webview-provider.md`).

---

## 3. No input injection on a headless host

`pengudroid tap <x> <y>` and `pengudroid key <code>` return `Ack` but the events are **not delivered** to the app. The daemon never logs `subscribed to input` because the compositor has no pointer focus in the headless backend.

**Evidence:** launch log contains no `injected … on surface N` lines; frame counter stops changing after `tap`.

**Impact:** you cannot script consent dialogs, navigation, or video playback in a headless container. Interacting with the app requires a real interactive Wayland session.

---

## 4. No GPU ⇒ no visible window

`eglSwapBuffers` fails with `dmabuf export returned an invalid fd` (llvmpipe + no DRI). The window is therefore never *presented*. However `PENGUDROID_DUMP_FRAME` still yields real rendered frames (720×1280 PPM).

---

## 5. YouTube consent gate

Because the WebView reaches the real `https://m.youtube.com`, the app shows Google's cookie-consent page first. This is expected and not a bug. It blocks reaching actual content until accepted (which needs input injection, see #3).

---

## 6. YTCache captures only during playback

YTCache tees media segments from the player's `MediaSource.appendBuffer` while a video is playing. An idle app (or one stuck on consent) produces **no** capture files under `Android/data/com.ytcache.app/files/YTCache/`. Observing capture requires real playback.

---

## 7. Hardware video decode not available

No GPU/VA-API on the host → hardware video decoding is absent; software decode paths apply.

---

## What to do if you hit something else

Check upstream status and limitations:

- https://github.com/denizomer10/PenguDroid/blob/main/docs/STATUS.md
- https://github.com/denizomer10/PenguDroid/blob/main/docs/LIMITATIONS.md
