# Changelog

## 1.0.4-pre — Colab T4 full-movie transcription

Transcribe whole movies straight into Arabic `.srt` on a **free Google Colab T4 GPU** —
for the videos (or languages) the tiny on-device models can't do justice.

**Backend — [`colab/app.ipynb`](colab/app.ipynb) (open in Colab → Runtime → Run all)**
- Installs Hugging Face `transformers` + torch and loads
  `samil24/whisper-large-arabic-dialects-v5` — a Whisper large-v3 fine-tune for Arabic
  dialects — on the T4 in fp16. (faster-whisper can't load raw HF checkpoints, so this
  uses `transformers`, exactly as the model card shows.)
- FastAPI server on the Colab VM, exposed via a **free Cloudflare quick tunnel**
  (no signup) with a pyngrok fallback when `NGROK_AUTHTOKEN` is set.
- **Async job API** for movies: `POST /jobs` (multipart) or `POST /jobs/url` (direct
  audio link, e.g. YouTube/mux dumps) → `job_id`; `GET /jobs/{id}` progress; 
  `GET /jobs/{id}/srt` result; short clips can use the blocking `POST /transcribe`.
- Transcribes in **5-min overlapping windows** (midpoint-stitched to never drop a word)
  with real %/ETA progress — a 2 h movie ≈ 15–20 min on a T4.

**Client — [`colab/client.py`](colab/client.py)** (Python, only needs `requests` + ffmpeg)
- ffmpeg: video → 16 kHz mono WAV (same recipe as `stt.py`); auto-compresses to Opus
  when >90 MB (Cloudflare quick tunnels reject ~100 MB bodies; `--transport` to force).
- Upload → poll → download → run through `srtcore` → same branded
  `Movie.SubArabify.ar.srt` next to the video (same rules as app + addon).
- **Timeout-safe**: per-request timeouts, transient-poll tolerance, watchdog on upload,
  overall `--timeout` deadline (90 min default), and `--job <id>` resume after a
  disconnect or Colab session change.

**Housekeeping**
- Version bumped to `1.0.4-pre` (versionCode 5) across app, site, Jellyfin manifest, CI artifacts.
- CI now self-tests `colab/test_client.py` (mock backend + real ffmpeg e2e on runners).

## 1.0.3-beta — speech-to-text update

No subtitle file? The app/server now listens (WhisperSubs-style, fully offline).

**Android (on-device, Vosk small-en ~40 MB, downloaded once)**
- `SttEngine` + `AudioExtractor`: video audio → 16 kHz mono PCM stream
  (MediaCodec, SAF-safe, never a whole movie in RAM) → English words with real
  timestamps → grouped into cues → normal smart EN→AR translation
- Worker: no source ⇒ status `transcribing` with % progress ⇒ same branded
  `*.SubArabify.ar.srt`, same preview; log shows `STT OK`
- UI: STT line in the model card, mic icon + progress per item

**Jellyfin addon (`--stt` / `SUBARABIFY_STT=1`)**
- `stt.py`: ffmpeg (Jellyfin's bundled one first) + faster-whisper `tiny.en`
  (CPU int8, downloaded once) → word-timestamp cues → same translation/branding
- Graceful when uninstalled/missing ffmpeg: stays `pending` with a clear log line

**Limits (honest):** English audio only in this pass (small-en / tiny.en are
English models) — anything else stays `pending`; music-heavy content may
transcribe sparsely.

## 1.0.3-beta

Smart translation + real in-app value + Jellyfin addon, one icon everywhere.

**Translation quality (was: "translation is wrong")**
- Speaker labels (`JOHN:`) shielded from translation; sound cues (`[Music]`, `♪`) handled, not literally mangled
- Short cues joined into ~600-char context chunks before hitting the model
- Glossary pins the most common subtitle lines instantly (`Previously on` → `في الحلقة السابقة`, …)
- Arabic post-fix: `?`→`؟`, `,`→`،`, `;`→`؛`, spacing cleanup; timecodes never touched

**Speed (was: "lags between runs")**
- Chunked batches: ~10 model calls per movie instead of ~1000 sequential per-line calls
- Persistent translation memory (`TranslationMemory`, on-disk): repeated lines translated once, served forever
- Progress shown per movie (`translating… 120/400 lines`); bounded activity log

**Value in the app (was: "no real value added")**
- In-app subtitle preview: tap the subtitles icon on any item, cues render like a normal player (black bar, timings, العربية/English toggle)
- Works whether the source was found or not: success shows AR cues, weak-source shows original, pending explains what file to add
- On-device model status card (ready / downloads-once + memorized-lines count)

**Source finding (was: exact `.en.srt` or nothing)**
- Smart resolver: exact tagged → exact bare → fuzzy (`Movie.2023.1080p.WEB-DL.mkv` ↔ `movie_en.srt`) → any-language fallback → `.vtt` input (SRT + VTT both parsed, VTT `mm:ss.mmm` + cue settings supported)

**Timing safety (brand never hurts sync)**
- Dialogue timecodes copied bit-identical, verified by test
- Opening brand only inserted into a real free gap (≥3.2 s before cue #1); otherwise skipped, file NOTE + filename + end cue still carry the brand
- Closing brand always 1.5 s after the last cue

**Jellyfin addon (new)**
- `jellyfin-addon/`: native Python companion — runs on normal servers AND Android Termux (no .NET)
- On enable: downloads the small Arabic LLM (`Helsinki-NLP/opus-mt-en-ar`, ~200 MB) first, then translates current movies to `*.SubArabify.ar.srt` with the app name and untouched timings
- Same rules as the app (`srtcore.py` parity), persistent memory, Jellyfin library refresh hook, systemd unit, Termux runner, `manifest.json` 1.0.3-beta

**Look**
- One icon everywhere: `docs/icon.jpg` → app launcher icon, in-app hero, README, landing page (favicon + hero), Jellyfin manifest image
- Version bumped to `1.0.3-beta` (versionCode 4) across app, site, manifest, changelog
