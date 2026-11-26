# Installing the Android System WebView provider

Many modern Android apps (e.g. YouTube wrappers, WebView-based apps, many games' login screens — including `com.ytcache.app`) require a working **WebView** (Chromium). PenguDroid's shipped runtime **omits** the WebView APK, so such apps crash with:

```
Caused by: java.lang.UnsupportedOperationException
    at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:351)
```

Fix: extract the **real** `com.android.webview` from Google's official **API-37** system image and place it in the runtime tree.

---

## 1. Get the API-37 system image

The official Google image `system-images;android-37.0;default;x86_64` (revision 6) contains the `product` partition with `app/webview/webview.apk`.

```bash
curl -L -o sys37.zip \
  https://dl.google.com/android/repository/sys-img/android/x86_64-37.0_r06.zip
unzip sys37.zip         # gives x86_64/system.img (GPT), etc.
```

---

## 2. Extract the product partition

Use `lpunpack` (https://github.com/unix3dgforce/lpunpack) and `debugfs`.

```bash
# Split the GPT 'super' partition out of system.img
# (system.img = GPT: [vbmeta 2048-4095] [super 4096-4935679])
dd if=x86_64/system.img of=super.img bs=512 skip=4096 count=$((4935679-4096+1))

python3 lpunpack.py -p product super.img out      # -> out/product.img (ext4)
debugfs -R "ls -l /app/webview" out/product.img   # shows webview.apk (333,998,538 bytes)
debugfs -R "dump /app/webview/webview.apk ./webview.apk" out/product.img
```

Verify:
```bash
aapt dump badging webview.apk | head -1
# package: name='com.android.webview' versionName='145.0.7632.218'
unzip -l webview.apk | grep 'lib/x86_64/libwebviewchromium.so'
```

---

## 3. Install into the PenguDroid runtime

`<R>` = `~pengu/.cache/pengudroid/runtime/android17-x86_64-modular-versions/<hash>`

```bash
R=$(ls -d ~pengu/.cache/pengudroid/runtime/android17-x86_64-modular-versions/*/ | head -1)

mkdir -p "$R/components/platform/product/app/webview"
install -m 444 webview.apk "$R/components/platform/product/app/webview/webview.apk"
```

The runtime's virtual mount table maps guest `/system/product` → `components/platform/product`, and `/system/lib64` → `components/native`. The WebView code also opens `libwebviewchromium_plat_support.so` (already present in `components/native`) via `/system/lib64`.

To be safe, provide host-side symlinks so the Android process's *real* `ANDROID_ROOT=/system` opens succeed:

```bash
ln -sfn "$R/components/platform/product" /system/product
ln -sfn "$R/components/native"            /system/lib64
```

---

## 4. Launch with WebView flags

```bash
export PENGUDROID_WEBVIEW=1
export PENGUDROID_WEBVIEW_SINGLE_PROCESS=1   # in-process renderer
export PENGUDROID_WEBVIEW_SOFTWARE=1          # software Canvas layers
export PENGUDROID_FRAMEWORK_LOOPER=1          # shared native message queue
```

Expected log milestones:

```
webview: framework verified com.android.webview 145.0.7632.218, 1 signer(s)
webview: verified provider package, loader and update service installed
webview: software Canvas layer selected
```

If you see `webview: framework rejected provider APK`, the APK is wrong/unsigned or mislocated. If you see `Failed to open APK '/system/product/app/webview/webview.apk'`, the symlinks/steps above were not applied.

---

## 5. Notes

- The provider APK is ~319 MB; extraction to `components/…/app/webview/` + `/system` symlinks is one-time per runtime.
- Do **not** set `PENGUDROID_WEBVIEW` without the provider installed — it just changes the failure mode.
- `PENGUDROID_PROBE_WEBVIEW=1` replaces the app content with a local HTML/JS probe (test-only; do not use for real runs).
