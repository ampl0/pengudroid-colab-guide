package dev.verify.asr;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import dev.tinyasr.app.TinyAsr;

/**
 * tinyasr verifier with a Google-STT-style LIVE experience:
 *  - "LIVE MIC"  : real-time captions while you speak (AudioRecord -> session).
 *  - "LIVE FILE" : play a loaded audio file in real time and watch the words
 *                  stream in, exactly like live captions over playback.
 *  - "VERIFY"    : whole-file probe across every pack language (diagnostic).
 */
public class MainActivity extends Activity {

    private static final int RQ_MODEL = 1001;
    private static final int RQ_AUDIO = 1002;
    private static final int RQ_MIC = 1003;
    private static final int RATE = 16000;
    private static final int CHUNK = 1600;            // 100 ms at 16 kHz

    private TextView status, result, partial;
    private Button verifyBtn, liveMicBtn, liveFileBtn;

    private File modelFile, vadFile, audioFile;
    private long models;                               // loaded model handle

    private boolean liveOn;
    private long liveSession;
    private final StringBuilder liveCommitted = new StringBuilder();
    private final List<String> packLangs = new ArrayList<>();
    private Thread micThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        result = findViewById(R.id.result);
        partial = findViewById(R.id.partial);
        verifyBtn = findViewById(R.id.verifyBtn);
        liveMicBtn = findViewById(R.id.liveMicBtn);
        liveFileBtn = findViewById(R.id.liveFileBtn);

        findViewById(R.id.pickModel).setOnClickListener(v -> open("*/*", RQ_MODEL));
        findViewById(R.id.pickAudio).setOnClickListener(v -> open("audio/*", RQ_AUDIO));
        verifyBtn.setOnClickListener(v -> verify());
        liveMicBtn.setOnClickListener(v -> toggleLiveMic());
        liveFileBtn.setOnClickListener(v -> liveFile());

        vadFile = new File(getCacheDir(), "silero_vad.onnx");
        copyAssetTo("silero_vad.onnx", vadFile);

        // --- auto-run hooks for headless/CI testing (no UI taps) ---
        File m = new File(getCacheDir(), "model.tzf");
        File a = new File(getCacheDir(), "audio.bin");
        boolean hasLiveFlag = new File(getCacheDir(), "live.flag").exists();
        boolean hasVerifyFlag = new File(getCacheDir(), "verify.flag").exists();
        if (m.exists() && a.exists()) {
            modelFile = m;
            audioFile = a;
            getWindow().getDecorView().post(() -> {
                models = loadModels();
                if (models == 0) return;
                if (hasLiveFlag) liveFile();
                else if (hasVerifyFlag) verify();
            });
        }
    }

    private long loadModels() {
        if (models != 0) return models;
        long h = TinyAsr.nativeLoadPaths(modelFile.getAbsolutePath(),
                vadFile.getAbsolutePath(), 0);
        if (h == 0) {
            status.setText("LOAD FAILED (bad model or VAD)");
            return 0;
        }
        packLangs.clear();
        int n = TinyAsr.nativeLangCount(h);
        for (int i = 0; i < n; i++) packLangs.add(TinyAsr.nativeLang(h, i));
        status.setText("model loaded: langs " + packLangs
                + " · chunk=" + TinyAsr.nativeChunkMs(h) + "ms");
        return h;
    }

    private String defaultLang() {
        return packLangs.isEmpty() ? "en" : packLangs.get(0);
    }

    private void open(String mime, int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        startActivityForResult(i, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        File dst = new File(getCacheDir(), requestCode == RQ_MODEL ? "model.tzf" : "audio.bin");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) return;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            status.setText("copy failed: " + e.getMessage());
            return;
        }
        models = 0;
        if (requestCode == RQ_MODEL) {
            modelFile = dst;
            status.setText("model: " + dst.length() / 1048576 + " MB — now pick audio");
        } else {
            audioFile = dst;
            status.setText("audio: " + dst.length() / 1024 + " KB");
        }
        result.setText("");
        partial.setText("");
    }

    // ------------------------------------------------------------------ mic --

    private void toggleLiveMic() {
        if (liveOn) { stopLiveMic(); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, RQ_MIC);
            return;
        }
        startLiveMic();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] gr) {
        super.onRequestPermissionsResult(code, perms, gr);
        if (code == RQ_MIC && gr.length > 0 && gr[0] == PackageManager.PERMISSION_GRANTED) {
            startLiveMic();
        } else {
            status.setText("microphone denied");
        }
    }

    private void startLiveMic() {
        models = loadModels();
        if (models == 0) return;
        liveOn = true;
        liveCommitted.setLength(0);
        result.setText("");
        partial.setText("");
        liveSession = TinyAsr.nativeSessionNew(models, defaultLang(), defaultLang(), 0);
        if (liveSession == 0) { liveOn = false; status.setText("session failed"); return; }
        status.setText("LIVE (mic) — speak…");
        verifyBtn.setEnabled(false);
        liveMicBtn.setText("Stop live");
        final int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        micThread = new Thread(() -> {
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 8192));
            try {
                rec.startRecording();
                short[] buf = new short[CHUNK];
                while (liveOn) {
                    int r = rec.read(buf, 0, CHUNK);
                    if (r <= 0) continue;
                    float[] f = new float[r];
                    for (int i = 0; i < r; i++) f[i] = buf[i] / 32768f;
                    pushChunk(f, r);
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("mic error: " + e.getMessage()));
            } finally {
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
            }
        });
        micThread.start();
    }

    private void stopLiveMic() {
        liveOn = false;
        if (micThread != null) {
            try { micThread.join(2000); } catch (InterruptedException ignored) {}
            micThread = null;
        }
        finishLiveSession();
    }

    // ------------------------------------------------------------------ file --

    /** Simulate playback of the loaded audio, showing live captions as it goes. */
    private void liveFile() {
        models = loadModels();
        if (models == 0 || audioFile == null) {
            status.setText("need a model and an audio file first");
            return;
        }
        final float[] pcm;
        try {
            pcm = decode(audioFile);
        } catch (Exception e) {
            status.setText("decode failed: " + e.getMessage());
            return;
        }
        liveCommitted.setLength(0);
        result.setText("");
        partial.setText("");
        status.setText("LIVE (file) — " + String.format(java.util.Locale.US, "%.1f", pcm.length / 16000.0) + "s");
        verifyBtn.setEnabled(false);
        liveFileBtn.setEnabled(false);

        new Thread(() -> {
            final long session = TinyAsr.nativeSessionNew(models, defaultLang(), defaultLang(), 0);
            if (session == 0) {
                runOnUiThread(() -> status.setText("session failed"));
                return;
            }
            for (int off = 0; off < pcm.length && !isFinishing(); off += CHUNK) {
                int n = Math.min(CHUNK, pcm.length - off);
                pushChunk(pcm, off, n, session);
                try { Thread.sleep(90); } catch (InterruptedException e) { break; }  // real-time pace
            }
            String fin = TinyAsr.nativeSessionFinish(session);
            handleEvents(fin);
            TinyAsr.nativeSessionFree(session);
            runOnUiThread(() -> {
                partial.setText("");
                verifyBtn.setEnabled(true);
                liveFileBtn.setEnabled(true);
                status.setText("LIVE (file) done");
            });
        }).start();
    }

    // ------------------------------------------------------------------ push --

    private void pushChunk(float[] f, int n) {
        long s = liveSession;
        if (s == 0) return;
        String json = TinyAsr.nativeSessionPush(s, f, n);
        if (json != null) handleEvents(json);
    }

    private void pushChunk(float[] pcm, int off, int n, long session) {
        float[] sub = new float[n];
        System.arraycopy(pcm, off, sub, 0, n);
        String json = TinyAsr.nativeSessionPush(session, sub, n);
        if (json != null) handleEvents(json);
    }

    /** Apply a JSON event array: commits append, partials replace the live line. */
    private void handleEvents(String json) {
        if (json == null) return;
        try {
            final StringBuilder committed = new StringBuilder(liveCommitted.toString());
            final StringBuilder[] part = {new StringBuilder()};
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type");
                if ("commit".equals(type)) {
                    JSONArray words = o.optJSONArray("words");
                    if (words != null) {
                        for (int k = 0; k < words.length(); k++) {
                            JSONObject w = words.optJSONObject(k);
                            if (w == null) continue;
                            String t = w.optString("text");
                            if (t.isEmpty()) continue;
                            if (committed.length() > 0) committed.append(' ');
                            committed.append(t);
                        }
                    }
                } else if ("partial".equals(type)) {
                    part[0].setLength(0);
                    JSONArray open = o.optJSONArray("open");
                    if (open != null) {
                        for (int k = 0; k < open.length(); k++) {
                            JSONObject w = open.optJSONObject(k);
                            if (w != null && part[0].length() > 0) part[0].append(' ');
                            if (w != null) part[0].append(w.optString("text"));
                        }
                    }
                }
            }
            runOnUiThread(() -> {
                liveCommitted.setLength(0);
                liveCommitted.append(committed);
                result.setText(liveCommitted.toString());
                partial.setText(part[0].toString());
            });
        } catch (Exception e) {
            // ignore malformed event
        }
    }

    private void finishLiveSession() {
        long s = liveSession;
        liveSession = 0;
        if (s != 0) {
            String fin = TinyAsr.nativeSessionFinish(s);
            handleEvents(fin);
            TinyAsr.nativeSessionFree(s);
        }
        runOnUiThread(() -> {
            partial.setText("");
            verifyBtn.setEnabled(true);
            liveMicBtn.setText("Live mic");
            liveFileBtn.setEnabled(true);
            status.setText(liveCommitted.length() == 0 ? "no text" : "done");
        });
    }

    // ------------------------------------------------------------------ verify --

    private void verify() {
        models = loadModels();
        if (models == 0 || audioFile == null) {
            status.setText("Pick a model and audio first.");
            return;
        }
        result.setText("working…");
        partial.setText("");
        new Thread(() -> {
            String out;
            try {
                out = runVerify();
            } catch (Throwable t) {
                out = "ERROR: " + t.getMessage();
            }
            final String text = out;
            runOnUiThread(() -> result.setText(text));
        }).start();
    }

    private String runVerify() throws Exception {
        float[] pcm = decode(audioFile);
        if (pcm.length == 0) return "could not decode audio";

        StringBuilder sb = new StringBuilder();
        sb.append("engine v").append(TinyAsr.nativeVersion())
          .append("  chunk=").append(TinyAsr.nativeChunkMs(models)).append("ms")
          .append("  audio=").append(String.format(java.util.Locale.US, "%.2f", pcm.length / 16000.0))
          .append("s  langs(pack):");
        for (String l : packLangs) sb.append(' ').append(l);
        sb.append("\n");

        boolean any = false;
        for (String lang : packLangs) {
            String text = transcribeFile(models, pcm, lang);
            sb.append("  lang ").append(lang).append(" => ")
              .append(text.isEmpty() ? "(empty — no text)" : text).append("\n");
            if (!text.isEmpty()) any = true;
        }
        sb.append("VERDICT: ").append(any
                ? "TEXT PRODUCED — in the app pick the language that matches"
                : "NO TEXT for any language — try a longer, clearer recording");
        return sb.toString();
    }

    private String transcribeFile(long models, float[] pcm, String lang) {
        long fh = TinyAsr.nativeFileNew(models, pcm, pcm.length, lang, 0);
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

    // ------------------------------------------------------------------ audio --

    private static float[] decode(File f) throws Exception {
        byte[] head = new byte[12];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(head);
            if (n >= 4 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                try { return decodeWav(f); } catch (Exception ignore) { }
            }
        }
        return decodePlatform(f);
    }

    private static float[] decodeWav(File f) throws Exception {
        byte[] all = new byte[(int) Math.min(f.length(), 1 << 28)];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        int p = 12, rate = 16000, channels = 1, bits = 16, dataOff = -1, dataLen = 0;
        boolean floatPcm = false;
        while (p + 8 <= all.length) {
            int id = fourcc(all, p), chunk = le32(all, p + 4);
            if (id == fourcc("fmt ")) {
                int fmt = le16(all, p + 8);
                channels = le16(all, p + 10);
                rate = le32(all, p + 12);
                bits = le16(all, p + 22);
                floatPcm = fmt == 3;
                if (!floatPcm && fmt != 1) throw new Exception("compressed wav");
            } else if (id == fourcc("data")) {
                dataOff = p + 8;
                dataLen = chunk < 0 ? all.length - dataOff : Math.min(chunk, all.length - dataOff);
            }
            p += 8 + chunk + (chunk & 1);
        }
        if (dataOff < 0 || channels < 1) throw new Exception("no data");
        ByteBuffer bb = ByteBuffer.wrap(all, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN);
        int n = dataLen / ((bits / 8) * channels);
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                if (bits == 16) sum += bb.getShort() / 32768f;
                else if (bits == 32) sum += floatPcm ? bb.getFloat() : bb.getInt() / 2147483648f;
                else if (bits == 8) sum += ((bb.get() & 0xff) - 128) / 128f;
                else throw new Exception("unsupported bits");
            }
            mono[i] = sum / channels;
        }
        return resample(mono, rate);
    }

    private static float[] decodePlatform(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat fmt = ex.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; format = fmt; break; }
        }
        if (track < 0) throw new Exception("no audio track");
        ex.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        while (!outDone) {
            if (!inDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                    if (size < 0) { codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer out = codec.getOutputBuffer(outIdx);
                if (out != null && info.size > 0) {
                    byte[] b = new byte[info.size];
                    out.position(info.offset);
                    out.get(b);
                    sink.write(b);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            }
        }
        byte[] raw = sink.toByteArray();
        int rateIn;
        try { rateIn = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); } catch (Exception e) { rateIn = RATE; }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 2;
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) mono[i] = bb.getShort() / 32768f;
        return resample(mono, rateIn);
    }

    private static float[] resample(float[] in, int from) {
        if (from == RATE || from <= 0) return in;
        int n = (int) ((long) in.length * RATE / from);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double pos = (double) i * from / RATE;
            int i0 = (int) pos, i1 = Math.min(i0 + 1, in.length - 1);
            double fr = pos - i0;
            out[i] = (float) (in[i0] * (1 - fr) + in[i1] * fr);
        }
        return out;
    }

    private static int fourcc(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off+1] & 0xff) << 8) | ((b[off+2] & 0xff) << 16) | ((b[off+3] & 0xff) << 24);
    }
    private static int fourcc(String s) {
        return s.charAt(0) | (s.charAt(1) << 8) | (s.charAt(2) << 16) | (s.charAt(3) << 24);
    }
    private static int le16(byte[] b, int off) { return (b[off] & 0xff) | ((b[off+1] & 0xff) << 8); }
    private static int le32(byte[] b, int off) { return le16(b, off) | (le16(b, off+2) << 16); }

    private void copyAssetTo(String asset, File dst) {
        try (InputStream in = getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Exception e) {
            status.setText("asset copy failed: " + e.getMessage());
        }
    }
}
