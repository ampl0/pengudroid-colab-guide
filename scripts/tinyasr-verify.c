/* tinyasr-verify — host-side verifier for the tinyasr engine.
 *
 * Feed it a recording (16 kHz mono float PCM) and it will:
 *   - show the pack's languages,
 *   - transcribe in the language(s) you ask (or in EVERY pack language),
 *   - report which language(s) actually produce text, so an "empty transcript"
 *     in the app is diagnosed as a language mismatch vs. a dead engine.
 *
 * Usage:
 *   tinyasr-verify <model.tzf> <silero_vad.onnx> <pcm.f32> \
 *       [--mode stream|file] [--langs LIST|auto]
 *
 * pcm.f32 = raw 16 kHz mono float32 samples in [-1,1]
 *            (produce it with convert_audio.py, or wav2f32.py).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "tinyasr.h"

static unsigned char *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb");
    if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); long s = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(s ? s : 1);
    *n = s ? fread(b, 1, s, f) : 0;
    fclose(f);
    return b;
}

/* Pull the concatenated "text":"..." values out of the engine's JSON. */
static void extract_text(const char *j, char *out, size_t cap) {
    size_t o = 0;
    out[0] = 0;
    if (!j) return;
    const char *p = j;
    while ((p = strstr(p, "\"text\":\"")) && o + 1 < cap) {
        p += strlen("\"text\":\"");
        while (*p && *p != '"' && o + 1 < cap) out[o++] = *p++;
        out[o++] = ' ';
    }
    if (o && out[o-1] == ' ') o--;
    out[o] = 0;
}

static size_t run_file(tinyasr_models *m, const float *pcm, size_t nsamp,
                       const char *langs, char *text, size_t cap) {
    tinyasr_file *f = tinyasr_file_new(m, pcm, nsamp, langs, 0);
    if (!f) { snprintf(text, cap, "(file_new failed)"); return 0; }
    size_t o = 0;
    const char *chunk;
    while ((chunk = tinyasr_file_next(f)) != NULL) {
        char piece[4096];
        extract_text(chunk, piece, sizeof piece);
        size_t n = strlen(piece);
        if (o + n + 2 < cap) {
            if (o && piece[0]) { text[o++] = ' '; }
            memcpy(text + o, piece, n); o += n; text[o] = 0;
        }
    }
    const char *lang = tinyasr_file_lang(f);
    tinyasr_file_free(f);
    return o; /* text length */
}

static size_t run_stream(tinyasr_models *m, const float *pcm, size_t nsamp,
                         const char *langs, const char *start, char *text, size_t cap) {
    tinyasr_session *s = tinyasr_session_new(m, langs, start, NULL, 0);
    if (!s) { snprintf(text, cap, "(session_new failed)"); return 0; }
    char final[1 << 16] = {0};
    size_t o = 0;
    for (size_t off = 0; off < nsamp; off += 1600) {
        size_t n = nsamp - off < 1600 ? nsamp - off : 1600;
        const char *j = tinyasr_session_push(s, pcm + off, n);
        if (j && j[0] && strstr(j, "\"commit\"")) {
            char piece[4096];
            extract_text(j, piece, sizeof piece);
            /* only commit (final) words accumulate */
            if (strstr(j, "\"type\":\"commit\"")) {
                size_t len = strlen(piece);
                if (o + len + 2 < sizeof final) {
                    if (o && piece[0]) final[o++] = ' ';
                    memcpy(final + o, piece, len); o += len; final[o] = 0;
                }
            }
        }
    }
    const char *fin = tinyasr_session_finish(s);
    if (fin && strstr(fin, "\"type\":\"commit\"")) {
        char piece[4096];
        extract_text(fin, piece, sizeof piece);
        size_t len = strlen(piece);
        if (o + len + 2 < sizeof final) {
            if (o && piece[0]) final[o++] = ' ';
            memcpy(final + o, piece, len); o += len; final[o] = 0;
        }
    }
    snprintf(text, cap, "%s", final);
    tinyasr_session_free(s);
    return o;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr,
            "usage: %s <model.tzf> <silero_vad.onnx> <pcm.f32>\n"
            "       [--mode stream|file] [--langs LIST|auto]\n"
            "  LIST may be e.g. en or en,ru,uk ; 'auto' tries every pack language.\n",
            argv[0]);
        return 2;
    }
    const char *mode = "file";
    const char *langs = "auto";
    for (int i = 4; i < argc; i++) {
        if (!strcmp(argv[i], "--mode") && i+1 < argc) mode = argv[++i];
        else if (!strcmp(argv[i], "--langs") && i+1 < argc) langs = argv[++i];
        else { fprintf(stderr, "unknown arg: %s\n", argv[i]); return 2; }
    }

    size_t an, vn;
    unsigned char *asr = slurp(argv[1], &an);
    unsigned char *vad = slurp(argv[2], &vn);
    char err[256] = {0};
    tinyasr_models *m = tinyasr_load(asr, an, vad, vn, err, sizeof err);
    if (!m) { printf("LOAD FAILED: %s\n", err); return 1; }

    size_t bytes = 0, nsamp = 0;
    unsigned char *raw = slurp(argv[3], &bytes);
    if (bytes % 4) { fprintf(stderr, "pcm.f32 size not a multiple of 4\n"); return 2; }
    nsamp = bytes / 4;
    float *pcm = (float *)raw;

    printf("engine v%u  chunk=%ums  audio=%.2fs  langs(pack):",
           tinyasr_version(), tinyasr_chunk_ms(m), nsamp / 16000.0);
    for (uint32_t i = 0; i < tinyasr_lang_count(m); i++) printf(" %s", tinyasr_lang(m, i));
    printf("\n");

    int any = 0;
    if (!strcmp(langs, "auto")) {
        uint32_t n = tinyasr_lang_count(m);
        if (n == 0) { printf("pack has no languages\n"); return 3; }
        for (uint32_t i = 0; i < n; i++) {
            const char *L = tinyasr_lang(m, i);
            char text[1 << 16] = {0};
            size_t len = !strcmp(mode, "stream")
                       ? run_stream(m, pcm, nsamp, L, L, text, sizeof text)
                       : run_file(m, pcm, nsamp, L, text, sizeof text);
            printf("  lang %-10s => %s\n", L, len ? text : "(empty — no text)");
            if (len) any = 1;
        }
    } else {
        char text[1 << 16] = {0};
        size_t len = !strcmp(mode, "stream")
                   ? run_stream(m, pcm, nsamp, langs, NULL, text, sizeof text)
                   : run_file(m, pcm, nsamp, langs, text, sizeof text);
        printf("  langs \"%s\" (%s) => %s\n", langs, mode, len ? text : "(empty — no text)");
        any = len != 0;
    }
    printf("VERDICT: %s\n", any ? "TEXT PRODUCED (engine works; in the app, pin to a language that matches)"
                                 : "NO TEXT for these language(s) — mismatch. Try the language you actually spoke.");
    tinyasr_free(m);
    free(asr); free(vad); free(raw);
    return any ? 0 : 3;
}
