# SubArabify — v0.2.3-alpha

![SubArabify icon](docs/icon.jpg)

**Automatic Arabic subtitles — every translation and transcription runs on a free Google Colab T4 backend.**

Pick a media folder. The app (or the Python client) finds each video and routes it:

1. **Already has Arabic?** → skip (`*.SubArabify.ar.srt` exists)
2. **Has an English `.srt`/`.vtt`?** → uploaded to the backend, which TRANSLATES it to Arabic (timings kept bit-identical)
3. **No subtitle at all?** → the audio track is extracted to a 16 kHz WAV (no FFmpeg on Android — MediaCodec does it) and the backend TRANSCRIBES it straight into Arabic with a Whisper large-v3 fine-tune for Arabic dialects

Either way you get a player-ready `MovieName.SubArabify.ar.srt` next to the video, with the SubArabify brand only in gaps that no dialogue uses, and an in-app preview that looks like normal subtitles.

---

## Download

| | |
|---|---|
| **Latest APK** | [SubArabify-latest.apk](https://muxd22-alt.github.io/SubArabify/SubArabify-latest.apk) |
| **Landing page** | [muxd22-alt.github.io/SubArabify](https://muxd22-alt.github.io/SubArabify/) |
| **Source** | [github.com/muxd22-alt/SubArabify](https://github.com/muxd22-alt/SubArabify) |
| **Backend notebook** | [`backend/SubArabify_Backend.ipynb`](backend/SubArabify_Backend.ipynb) — open in Colab, Run all |
| **Python client** | [`client/`](client/) — same routing & branding from any machine |
| **Jellyfin addon** | [`jellyfin-addon/`](jellyfin-addon/) — same engine on your server / **Termux (Android TV)**; QR on TV |

Built automatically on every push to `main` and published to GitHub Pages (same APK the site's Download button serves).

> Requires Android. Install from unknown sources / your file manager. Current builds are signed with the debug keystore (super-beta).

---

## Quick start

### 1 · Start the backend (once per session)

```text
https://colab.research.google.com/github/muxd22-alt/SubArabify/blob/main/backend/SubArabify_Backend.ipynb
```

Colab → **Runtime → Run all** (GPU = T4). It installs `transformers` + FastAPI, loads
`samil24/whisper-large-arabic-dialects-v5` (Arabic) and `Helsinki-NLP/opus-mt-en-ar`, and
boots a FastAPI server behind a **free Cloudflare quick tunnel** (no account; pyngrok
fallback if `NGROK_AUTHTOKEN` is set). The last cell prints the URL.

### 2 · Point the app at it

Open the app → **Backend card** → paste the tunnel URL → **Save**. The card flips to
`online` once the health probe succeeds (HTTP addresses are accepted). Then select a
folder, hit **Scan Now**, or leave monitoring on.

### 3 · Or use the Python client

```bash
pip install -r client/requirements.txt            # requests only
python client/client.py --media /path/to/movies --url https://xxxx.trycloudflare.com
```

The client has the same routing rules, `--sync` blocking mode, `--media` repeatable
folder scans, and `--job <id> --save-to <video>` resume after a disconnect. A 2 h movie
≈ **15–20 min** on the T4 with real % progress. `--transport auto|wav|opus` switches to
Opus when a big WAV would exceed the tunnel's ~100 MB body limit.

### 4 · Or the Jellyfin addon — one engine, everywhere

[`jellyfin-addon/`](jellyfin-addon/) is a third front door for the **same backend**: it
scans a library on a Jellyfin server **or** in **Termux** (phone or Android TV), routes
each movie with the identical rules, and writes `Movie.SubArabify.ar.srt`.

```bash
pip install -r jellyfin-addon/requirements.txt
python jellyfin-addon/subarabify_jellyfin.py --media /media/movies --watch
```

The whole point is **one URL, used everywhere** — the exact same tunnel URL you paste
into the app's Backend card. No typing needed on a TV:

| How you get the URL onto the TV / phone | Command |
|---|---|
| **QR code printed on the TV** (scan with any phone camera → paste into the app) | `python jellyfin-addon/subarabify_jellyfin.py --qrcode` |
| **LAN copy endpoint** (open `http://<jellyfin-host>:8477/subarabify/config` in a phone browser) | `python jellyfin-addon/subarabify_jellyfin.py --serve` |
| **Termux universal runner** (prompts once, saves to `config.json`) | `./jellyfin-addon/run-termux.sh --media ~/storage/movies --watch` |

Config precedence everywhere: `--url` flag **>** `$SUBARABIFY_COLAB_URL` **>**
`jellyfin-addon/config.json` → `"colab_url"`. The Android app reads the same URL from
its Backend card; the client from `--url`/env; the addon from config/env/flag. Same
routing, same branding, same backend — device does not change the result.

---

## The API (what both the app and client call)

| Endpoint | Purpose |
|---|---|
| `GET /health` | `status`, `version`, `model_loaded`, `mt_loaded`, `device`, `queued` |
| `POST /jobs` | multipart `srt=` → translate job · `file=` (16 kHz WAV) → transcribe job |
| `GET /jobs/{id}` | progress (0–1), status, message |
| `GET /jobs/{id}/srt` | finished plain SRT (409 while running) — **always returned unbranded** |
| `POST /jobs/url` | transcribe from a direct audio URL |
| `POST /transcribe` | blocking transcribe for short clips |

Jobs are async and queue server-side; ~8 finished jobs are kept before the oldest is evicted.

## Subtitle branding (honest rules)

| Where | What |
|---|---|
| **Start** | Short `— SubArabify —` cue, **only** if a real gap exists before cue #1 |
| **Middle** | Free — dialogue only, no watermark |
| **End** | Short `— SubArabify —` cue ~1.5 s after the last line |
| **Filename** | Always `*.SubArabify.ar.srt` |
| **Inside the file** | A hidden `NOTE` for anyone who opens the `.srt` in a text editor |
| **Timings** | Dialogue timecodes bit-identical to the source — sync never hurt |

The same rules live in two places that never drift: `SrtParser.kt` (Android) and
`client/srtcore.py` — the Jellyfin addon ships a byte-identical copy of the latter
(`jellyfin-addon/srtcore.py`), all proven by the same tests.

## Features

| Feature | Reality |
|---|---|
| Routing | skip done → translate existing `.srt` → transcribe the audio — all handled by one worker |
| Translation | `Helsinki-NLP/opus-mt-en-ar` on the T4, per-cue, timings preserved |
| Transcription | `samil24/whisper-large-arabic-dialects-v5` (raw HF checkpoint, fp16) – Arabic-native output |
| App engine | Thin HTTP client (`BackendClient.kt`) — no ML Kit, no Vosk, no local models at all |
| Jellyfin addon | Same backend, server or Termux — `jellyfin-addon/`, QR on TV, LAN config endpoint |
| Audio extraction | `AudioExtractor` (MediaCodec + resampler) → 16 kHz mono WAV, streamed, no FFmpeg |
| Resume | Poll tolerates tunnel blips; long deadlocks fail cleanly and a later scan re-routes |
| Weak-source guard | promo `.srt` (< 30 cues / < 2 KB) marked `weak_source`, never sent |
| Preview | In-app subtitle preview with العربية / English toggle |
| Background scans | WorkManager at your interval (15 min – 6 h) |
| Smart skip | already has `.SubArabify.ar.srt` → done; user skip list supported |

## Repository layout

```
SubArabify/
├── backend/
│   ├── SubArabify_Backend.ipynb   # THE engine — Colab T4: FastAPI + Whisper-AR + opus-mt-en-ar + tunnel
│   ├── test_backend.py            # 17 smoke checks (torch stubbed, no GPU needed)
│   ├── README.md                  # notebook walkthrough
│   └── requirements.txt
├── client/
│   ├── client.py                  # routing CLI: --media / --url / --sync / --job resume / --transport
│   ├── srtcore.py                 # SRT rules shared with the Android app (parity tests)
│   ├── test_client.py             # 12 mock-backend tests (runs in CI)
│   ├── test_srtcore.py            # 7 srtcore tests (runs in CI)
│   └── README.md
├── jellyfin-addon/
│   ├── subarabify_jellyfin.py     # unified addon: --media/--watch/--qrcode/--serve/--job resume
│   ├── srtcore.py                 # byte-identical copy of client/srtcore.py (parity, never drifts)
│   ├── config.json                # the one config: colab_url, media, intervals, port
│   ├── manifest.json              # Jellyfin repository catalog (checksum injected by CI)
│   ├── make_manifest.py           # zip SHA-256 → public/jellyfin-manifest.json
│   ├── run-termux.sh              # Termux universal runner (phone + Android TV)
│   ├── test_addon.py              # config/QR/LAN-endpoint/routing/mock-backend tests (in CI)
│   └── README.md
├── app/                           # Android — container, not the engine
│   └── src/main/java/com/subarabify/
│       ├── engine/BackendClient.kt   # /health, /jobs, /jobs/{id}, /jobs/{id}/srt
│       ├── worker/SubArabifyWorker.kt# scan → route → brand → write .SubArabify.ar.srt
│       ├── data/AudioExtractor.kt    # video audio → 16 kHz mono WAV (MediaCodec)
│       ├── data/SrtParser.kt         # parse (SRT+VTT) + timing-safe branded builder
│       ├── data/StorageHelper.kt     # SAF helpers + smart/fuzzy source resolver
│       └── ui/MainActivity.kt        # folder pick, backend URL, monitoring, preview
├── docs/                          # landing page (docs/index.html) + icon
└── .github/workflows/build-and-deploy.yml
```

## Building locally

```bash
gradle assembleRelease --no-daemon
```

APK output: `app/build/outputs/apk/release/`

## Why the clean slate in 0.2.3-alpha

Earlier betas shipped an on-device ML Kit translator + a Vosk speech-to-text engine on the
phone, and a Colab notebook ("1.0.4-pre") for transcription only. The history and those
stacks were removed: the phone now holds no models at all, and translate +
transcribe share one backend, one API, and one set of SRT rules (introduced as the clean
slate in 0.2.2-beta). The Jellyfin addon lives again as a **backend-driven** companion
(the old whisper.cpp/llama.cpp engine is gone) — `jellyfin-addon/` is just another front
door for the Colab backend, with a QR code to carry the same URL onto the TV.

## Branding / icon

The app icon, launcher icon, landing page, and README header all use [`docs/icon.jpg`](docs/icon.jpg).

## License

Open-source. Privacy-first.