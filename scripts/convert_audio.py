#!/usr/bin/env python3
"""convert_audio.py — turn any audio file into the 16 kHz mono float32 PCM
that tinyasr expects (-1..1), the same form the app pushes.

Uses ffmpeg if present (supports mp3, m4a, ogg, flac, wav, ...). Falls back to
reading a WAV directly.
"""
import struct
import subprocess
import sys
import wave


def via_ffmpeg(src, dst):
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", src,
         "-ac", "1", "-ar", "16000", "-f", "f32le", dst],
        check=True,
    )


def via_wav(src, dst):
    w = wave.open(src, "rb")
    if w.getsampwidth() != 2:
        raise SystemExit("need 16-bit PCM WAV, or install ffmpeg")
    ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
    raw = w.readframes(n)
    w.close()
    samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
    if ch > 1:
        samples = [sum(samples[i:i + ch]) / ch for i in range(0, len(samples), ch)]
    if rate != 16000:
        # linear resample to 16 kHz
        m = int(len(samples) * 16000 / rate)
        out = []
        for i in range(m):
            pos = i * rate / 16000
            i0 = int(pos); i1 = min(i0 + 1, len(samples) - 1)
            fr = pos - i0
            out.append(samples[i0] * (1 - fr) + samples[i1] * fr)
        samples = out
    with open(dst, "wb") as f:
        f.write(struct.pack("<%df" % len(samples), *[s / 32768.0 for s in samples]))
    print(f"{len(samples)/16000:.2f}s -> {dst}", file=sys.stderr)


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: convert_audio.py in.mp3 out.f32")
    src, dst = sys.argv[1], sys.argv[2]
    if src.lower().endswith(".wav"):
        try:
            via_wav(src, dst); return
        except Exception:
            pass
    try:
        via_ffmpeg(src, dst)
        print(f"converted -> {dst} (16 kHz mono f32)", file=sys.stderr)
    except (OSError, subprocess.CalledProcessError) as e:
        raise SystemExit("ffmpeg failed: %s (install ffmpeg, or provide a 16-bit WAV)" % e)


if __name__ == "__main__":
    main()
