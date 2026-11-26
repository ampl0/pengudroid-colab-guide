package dev.verify.asr;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import dev.tinyasr.app.TinyAsr;

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

/** Minimal host-style verifier: pick a .tzf model + an audio file, then probe
 *  every pack language and show which one actually produces text. */
public class MainActivity extends Activity {

    private static final int RQ_MODEL = 1001;
    private static final int RQ_AUDIO = 1002;
    private static final int RATE = 16000;

    private TextView status, result;
    private Button verifyBtn;
    private File modelFile, vadFile, audioFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        result = findViewById(R.id.result);
        verifyBtn = findViewById(R.id.verifyBtn);

        findViewById(R.id.pickModel).setOnClickListener(v ->
                open("*/*", RQ_MODEL));
        findViewById(R.id.pickAudio).setOnClickListener(v ->
                open("audio/*", RQ_AUDIO));
        verifyBtn.setOnClickListener(v -> verify());

        vadFile = new File(getCacheDir(), "silero_vad.onnx");
        copyAssetTo("silero_vad.onnx", vadFile);

        // Auto-run hook for headless testing: if a model and audio are already
        // in the cache dir, verify immediately without needing UI taps.
        File m = new File(getCacheDir(), "model.tzf");
        File a = new File(getCacheDir(), "audio.bin");
        if (m.exists() && a.exists()) {
            modelFile = m;
            audioFile = a;
            getWindow().getDecorView().post(() -> verify());
        }
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
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
        if (requestCode == RQ_MODEL) {
            modelFile = dst;
            status.setText("model: " + dst.getName() + " (" + dst.length()/1048576 + " MB)");
        } else {
            audioFile = dst;
            status.setText("audio: " + dst.getName() + " (" + dst.length()/1024 + " KB)");
        }
        result.setText("");
    }

    private void verify() {
        if (modelFile == null || audioFile == null || !vadFile.exists()) {
            status.setText("Pick a model and an audio file first.");
            return;
        }
        result.setText("working…");
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
        long models = TinyAsr.nativeLoadPaths(modelFile.getAbsolutePath(),
                vadFile.getAbsolutePath(), 0);
        if (models == 0) return "LOAD FAILED (bad model or VAD)";

        float[] pcm = decode(audioFile);
        if (pcm.length == 0) return "could not decode audio";

        StringBuilder sb = new StringBuilder();
        sb.append("engine v").append(TinyAsr.nativeVersion())
          .append("  chunk=").append(TinyAsr.nativeChunkMs(models)).append("ms")
          .append("  audio=").append(String.format(java.util.Locale.US, "%.2f", pcm.length / 16000.0))
          .append("s  langs(pack):");
        int langs = TinyAsr.nativeLangCount(models);
        for (int i = 0; i < langs; i++) sb.append(' ').append(TinyAsr.nativeLang(models, i));
        sb.append("\n");

        boolean any = false;
        for (int i = 0; i < langs; i++) {
            String lang = TinyAsr.nativeLang(models, i);
            String text = transcribeFile(models, pcm, lang);
            sb.append("  lang ").append(lang).append(" => ")
              .append(text.isEmpty() ? "(empty — no text)" : text).append("\n");
            if (!text.isEmpty()) any = true;
        }
        TinyAsr.nativeFree(models);
        sb.append("VERDICT: ").append(any
                ? "TEXT PRODUCED (engine works — in the app pick the language that matches)"
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

    // --- audio decode helper ------------------------------------------------

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
