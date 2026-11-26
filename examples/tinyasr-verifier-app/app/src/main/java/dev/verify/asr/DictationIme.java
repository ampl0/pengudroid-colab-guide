package dev.verify.asr;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import dev.tinyasr.app.TinyAsr;

/**
 * A minimal IME that lets you dictate into ANY text box, Google-ASR style:
 * tap the mic button, talk, and the text is typed into the focused field.
 * Uses the tinyasr engine on-device (offline, no network).
 *
 * Supported on the phone; not verifiable on the headless test rig.
 */
public class DictationIme extends InputMethodService {

    private static final int RATE = 16000;
    private static final int CHUNK = 1600;

    private Button micBtn;
    private boolean recording = false;
    private Thread recThread = null;
    private long models = 0;
    private final List<String> langs = new ArrayList<>();
    private File vadFile;

    @Override
    public View onCreateInputView() {
        View v = getLayoutInflater().inflate(R.layout.ime_keyboard, null);
        micBtn = v.findViewById(R.id.micBtn);
        micBtn.setOnClickListener(x -> toggle());
        prepareEngine();
        return v;
    }

    private void prepareEngine() {
        vadFile = new File(getCacheDir(), "silero_vad.onnx");
        copyAssetTo("silero_vad.onnx", vadFile);
        File model = new File(getCacheDir(), "model.tzf");
        if (!model.exists()) {
            micBtn.setText(getString(R.string.ime_dictate) + "\n(pick a model in the app first)");
            return;
        }
        models = TinyAsr.nativeLoadPaths(model.getAbsolutePath(), vadFile.getAbsolutePath(), 0);
        langs.clear();
        int n = TinyAsr.nativeLangCount(models);
        for (int i = 0; i < n; i++) langs.add(TinyAsr.nativeLang(models, i));
    }

    private String selectedLang() {
        for (String l : langs) {
            String c = l.toLowerCase(java.util.Locale.ROOT);
            if (!c.equals("en") && !c.equals("eng")) return l;
        }
        return langs.isEmpty() ? "en" : langs.get(0);
    }

    private void toggle() {
        if (models == 0) { return; }
        if (recording) stopAndCommit();
        else startRecording();
    }

    private void startRecording() {
        recording = true;
        micBtn.setText("… listening (tap to stop) …");
        final int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        recThread = new Thread(() -> {
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 8192));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                rec.startRecording();
                short[] buf = new short[CHUNK];
                while (recording) {
                    int r = rec.read(buf, 0, CHUNK);
                    if (r > 0) {
                        byte[] b = new byte[r * 2];
                        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < r; i++) bb.putShort(buf[i]);
                        try { out.write(b); } catch (Exception ignored) {}
                    }
                }
            } finally {
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
            }
            byte[] pcm16 = out.toByteArray();
            if (pcm16.length > 0) {
                String text = transcribePcm16(pcm16);
                new Handler(Looper.getMainLooper()).post(() -> {
                    micBtn.setText(getString(R.string.ime_dictate));
                    commit(text);
                });
            }
        });
        recThread.start();
    }

    private void stopAndCommit() {
        recording = false;
        if (recThread != null) {
            try { recThread.join(2000); } catch (InterruptedException ignored) {}
            recThread = null;
        }
    }

    private String transcribePcm16(byte[] pcm16) {
        int n = pcm16.length / 2;
        float[] pcm = new float[n];
        ByteBuffer bb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) pcm[i] = bb.getShort() / 32768f;
        String lang = selectedLang();
        long fh = TinyAsr.nativeFileNew(models, pcm, n, lang, 0);
        if (fh == 0) return "";
        StringBuilder sb = new StringBuilder();
        try {
            String chunk;
            while ((chunk = TinyAsr.nativeFileNext(fh)) != null) {
                try {
                    JSONArray arr = new JSONArray(chunk);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String t = o.optString("text", "");
                        if (t.isEmpty()) continue;
                        if (sb.length() > 0 && !o.optBoolean("join", false)) sb.append(' ');
                        sb.append(t);
                    }
                } catch (Exception ignored) { }
            }
        } finally {
            TinyAsr.nativeFileFree(fh);
        }
        return sb.toString().trim();
    }

    private void commit(String text) {
        if (text.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void copyAssetTo(String asset, File dst) {
        try (InputStream in = getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        recording = false;
        if (models != 0) TinyAsr.nativeFree(models);
        models = 0;
        super.onDestroy();
    }
}
