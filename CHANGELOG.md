# Changelog

## 0.2.3-alpha — same engine, versioned

First cut re-releasing the clean-slate stack under a new version line. Everything from
0.2.2-beta carries over; this tag's job is to lock the **Jellyfin addon** addition that
0.2.2-beta CI shipped days after its tag.

- App: `versionCode` 23, `versionName "0.2.3-alpha"` (label, User-Agent, multipart
  boundary, brand line all bumped).
- **Jellyfin addon now in the release**: `jellyfin-addon/` is a backend-driven companion
  of the Colab backend — scan a library on a server or in Termux, route each movie
  (skip / translate / transcribe), write branded `*.SubArabify.ar.srt`. One URL
  everywhere; `--qrcode` prints a scannable QR on the TV; `--serve` exposes
  `http://<host>:8477/subarabify/config`; `run-termux.sh` is the universal runner.
- CI builds, self-tests the addon, and publishes `SubArabify-0.2.3-alpha.apk` + the
  addon ZIP + `jellyfin-manifest.json` to GitHub Pages.
- Docs & landing page updated to v0.2.3-alpha with the unified one-URL story.

## 0.2.2-beta — clean slate, one backend

A reset that takes the lesson of the earlier betas: the phone should only carry the
routing, not the models. Every translation and transcription now runs on a free Google
Colab T4 backend, and the repo history was squashed to a single commit that tells only
this story.

**One hidden engine — [`backend/SubArabify_Backend.ipynb`](backend/SubArabify_Backend.ipynb)**
- Colab T4 → installs Hugging Face `transformers` + FastAPI, loads
  `samil24/whisper-large-arabic-dialects-v5` (Whisper large-v3 fine-tune for Arabic
  dialects, fp16) and `Helsinki-NLP/opus-mt-en-ar`, then bootstraps with a free
  Cloudflare quick tunnel (no account; pyngrok fallback).
- Async job API: `POST /jobs` (`srt=` translate · `file=` transcribe) → `GET /jobs/{id}`
  progress → `GET /jobs/{id}/srt`. 5-min overlapping windows, midpoint-stitched.
- Always returns **plain, unbranded SRT** — the client brands it with the same rules everywhere.

**Python client — [`client/`](client/)**
- Same routing as Android: existing `.SubArabify.ar.srt` → skip; English `.srt` →
  translate; none → ffmpeg-extract 16 kHz WAV → transcribe.
- `--media` (repeatable) recursive scans, `--sync` blocking mode, `--transport auto|wav|opus`
  (Opus keeps big movies under the tunnel's ~100 MB body cap), `--job <id>` resume,
  `--timeout` deadline (default 90 min).
- 19 CI-run tests (12 client + 7 srtcore) against a mock backend — no ffmpeg needed.

**Android — thin container**
- `BackendClient.kt`: `/health` probe (model + MT status), multipart uploads, tolerant
  polling (`MAX_POLL_MISSES=12`), 6 h deadline.
- Worker routes: existing Arabic → done; smart source resolver finds `.en.srt` →
  translate; else `AudioExtractor` streams a 16 kHz mono WAV (MediaCodec, no FFmpeg) →
  transcribe. Returns are branded via `SrtParser.buildBrandedSrt` and written as
  `*.SubArabify.ar.srt` with in-app preview.
- Backend card in the UI to paste the tunnel URL (health-rendered `online`/`offline`).
- **Removed**: ML Kit translator, Vosk speech-to-text, translation memory, all their deps.
  `versionCode` 22, `versionName 0.2.2-beta`; `usesCleartextTraffic` enabled for tunnel URLs.

**Docs & housekeeping**
- Landing page, README, and CI rebuilt around the one-engine story. CI self-tests the
  Python client + the Jellyfin addon and ships `SubArabify-0.2.2-beta.apk`.

**Jellyfin addon — unified, backend-driven ([`jellyfin-addon/`](jellyfin-addon/))**
- Re-added the "smart way": the addon is now a **third front door for the same Colab
  backend** (the old on-device whisper.cpp/llama.cpp engine is gone). Runs on a Jellyfin
  server or in Termux (phone / Android TV); identical routing + branding
  (`srtcore.py` is a byte-identical copy of `client/srtcore.py`).
- **One URL everywhere**: `--url` > `$SUBARABIFY_COLAB_URL` > `config.json` `colab_url` —
  exactly the URL pasted into the app's Backend card.
- **QR on the TV for the Android app**: `python jellyfin-addon/subarabify_jellyfin.py
  --qrcode` prints a scannable QR of the backend URL (scan → paste into the app) plus an
  optional `--qr-png`; `--serve` exposes a LAN endpoint
  (`http://<host>:8477/subarabify/config`) for phone-safe copying.
- **Smart transport on the server**: files ≤ ~90 MB are uploaded directly (the backend
  converts with its own ffmpeg — no local ffmpeg needed), bigger ones are reduced to
  16 kHz WAV / Opus when a local ffmpeg exists (Jellyfin ships one).
- `run-termux.sh` universal runner prompts once for the URL and saves `config.json`.
- Added `test_addon.py` (config precedence, QR, LAN endpoint, routing, mock-backend
  round trip) — runs in CI alongside the existing suites.

## 1.0.4-pre — (removed) Colab T4 full-movie transcription

> This release's history was removed in 0.2.2-beta. The transcription-only notebook and
> its client belonged to a version whose on-device ML Kit/Vosk stacks were dropped.

## 1.0.3-beta — (removed) speech-to-text update

> Removed with the on-device engine in 0.2.2-beta.