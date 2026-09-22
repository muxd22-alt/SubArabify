# SubArabify Backend — v0.2.3-alpha

The **only engine** behind SubArabify: a self-contained Colab T4 notebook,
hidden behind a free public tunnel. It serves the client (`client/`) and the
Android app — nothing else does transcription or translation.

`SubArabify_Backend.ipynb` · open in Google Colab → **Runtime → Run all**
(runtime type **GPU / T4** — the first cell checks).

## What the backend does

Two async job pipelines, one T4:

| multipart field | pipeline | model |
|---|---|---|
| `srt=<English .srt>` | **translate** | `Helsinki-NLP/opus-mt-en-ar` — EN→AR, cue by cue, **original timings kept bit-identical** |
| `file=<audio>` | **transcribe** | `samil24/whisper-large-arabic-dialects-v5` — direct Arabic transcript of the soundtrack |

Both return **plain, unbranded SRT**. Branding happens client-side (shared
`srtcore` rules) so every SubArabify output is formatted identically.

## API

| Endpoint | What it does |
|---|---|
| `GET /health` | liveness, model state, queue depth |
| `POST /jobs` | multipart `file=<audio>` **or** `srt=<subtitle>` → `202 {"job_id", "kind", ...}` |
| `POST /jobs/url` | `{"url": "https://…/audio.wav"}` → server downloads it → transcribe job |
| `GET /jobs/{id}` | poll `{"status", "kind", "progress": 0–1, "message", "elapsed_s"}` |
| `GET /jobs/{id}/srt` | finished `.srt` text (`409` until done) |
| `POST /transcribe` | blocking upload (`file=`/`srt=`) → `.srt` text — **short clips only** |

A 2 h movie takes **~15–20 min** on a T4, so jobs are async by design: submit →
poll → download. The public URL changes every Colab session.

## Notes

* **Why `transformers`, not `faster-whisper`?** The whisper repo is a raw
  Transformers checkpoint (safetensors, ~2B params). faster-whisper only loads
  converted CTranslate2 models, so `samil24/whisper-arabic-dialects-v5` is
  loaded exactly as its model card shows.
* Transcribe very long movies as the T4's `language="ar"` generation handles
  dialects out of the box; no EN→AR hop is needed.
* One job at a time (single GPU); further submissions queue.
* Uploads > ~90 MB use Opus (Cloudflare quick tunnels cap request bodies near
  100 MB) — handled automatically by the client.
* The notebook is the hidden backend: nothing in the app or client discloses
  where it runs; it looks like any Colab job.

## Local sandbox tests (no GPU)

`python backend/test_backend.py` runs the cell-03 code with torch/transformers
stubbed: SRT parse/translate/typography pure checks + the full FastAPI surface
(health, job lifecycle, error propagation) through `TestClient`.