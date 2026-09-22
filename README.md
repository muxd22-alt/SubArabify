# SubArabify — v1.0.4-pre

![SubArabify icon](docs/icon.jpg)

**Automatic Arabic subtitles on your phone — offline, folder-first, privacy-first.**
**And now: full-movie Arabic transcription on a free Google Colab T4 GPU.**

Pick a media folder. SubArabify finds English `.srt`/`.vtt` files next to your videos,
translates them on-device, and writes a player-ready Arabic file beside each video —
with timings copied **bit-identical** and an in-app preview that looks like normal subtitles.
No subtitle at all? Run the optional **Colab backend** (`colab/`) to hear the whole movie
in Arabic with `samil24/whisper-large-arabic-dialects-v5` on a free T4.

---

## Download

| | |
|---|---|
| **Latest APK** | [SubArabify-latest.apk](https://muxd22-alt.github.io/SubArabify/SubArabify-latest.apk) |
| **Landing page** | [muxd22-alt.github.io/SubArabify](https://muxd22-alt.github.io/SubArabify/) |
| **Source** | [github.com/muxd22-alt/SubArabify](https://github.com/muxd22-alt/SubArabify) |
| **Jellyfin addon** | [`jellyfin-addon/`](jellyfin-addon/) — same engine for your media server / Termux |
| **Colab T4 backend** | [`colab/app.ipynb`](colab/app.ipynb) — free-GPU full-movie Arabic transcription |

Built automatically on every push to `main` and published to GitHub Pages (same APK the site's Download button serves).

> Requires Android. Install from unknown sources / your file manager. Current builds are signed with the debug keystore (super-beta).

---

## What's new in 1.0.4-pre — Colab T4 full-movie transcription

Squeeze the big Arabic model onto hardware that can run it. Your phone stays light;
a free Colab T4 does the listening.

1. **Open the notebook** — [colab.app.ipynb](https://colab.research.google.com/github/muxd22-alt/SubArabify/blob/main/colab/app.ipynb) →
   **Runtime → Run all** (GPU/T4). It installs Hugging Face `transformers` + FastAPI, loads
   `samil24/whisper-large-arabic-dialects-v5` (a Whisper large-v3 fine-tune for Arabic dialects),
   and opens a **free public URL** (Cloudflare quick tunnel — no account; pyngrok fallback).
2. **Transcribe a movie from your machine**:
   ```bash
   python colab/client.py "My Movie.mkv" --url https://xxxx.trycloudflare.com
   ```
   The client ffmpeg-extracts the 16 kHz mono track, uploads it, and polls the async job
   (a 2 h movie ≈ **15–20 min** on the T4 — real % progress + ETA). Keep the notebook tab open.
3. **Resume-proof**: transient tunnel failures are auto-retried; if everything dies, re-run with
   `python colab/client.py --url $URL --job <job_id> --save-to "My Movie.mkv"`.
4. **Same output rules, everywhere** — the returned `.srt` runs through `srtcore`, so the file is
   written next to the video as `Movie.SubArabify.ar.srt` with identical branding/timecode safety.

API: `POST /jobs` (upload) or `POST /jobs/url` (direct link) → `GET /jobs/{id}` progress →
`GET /jobs/{id}/srt`; plus a small-clips `POST /transcribe`. Full docs in [`colab/README.md`](colab/README.md).

## What's smarter in 1.0.3-beta (previous release)

| Old complaint | Fix |
|---|---|
| Translation is wrong | Speaker labels (`JOHN:`) shielded, sound cues (`[Music]`, `♪`) handled, short cues joined into context chunks, glossary pins common lines, Arabic punctuation post-fixed (`?`→`؟`, `,`→`،`) |
| Lags between runs | One ML Kit call **per line** → chunked batches (~10 calls/movie) + **persistent translation memory** (repeated lines translated once, served from disk forever) |
| No real value / nothing to see | New **in-app subtitle preview** — tap the subtitles icon on any item to see cues rendered like a normal player (العربية / English toggle, timings shown) |
| Only exact `.en.srt` found | Smart source resolver: exact → **fuzzy** (`Movie.2023.1080p.mkv` ↔ `movie_en.srt`) → any-language fallback → **`.vtt` input** |
| Brand might hurt sync | Brand cues only inserted into **real free gaps**; if the opening is crowded the brand is skipped (NOTE + filename + end cue still carry it). Dialogue timecodes are never touched |

## What it actually does

1. **Select a folder** — Movies, Downloads, or any tree via Android Storage Access Framework
2. **First run downloads the small Arabic model once** (ML Kit EN→AR) — then 100% offline
3. **Translate** — scans for videos with a usable subtitle source (see resolver above)
4. **No subtitle? Transcribe** — the audio track is transcribed offline on-device (Vosk small-en, downloaded once), then translated like a normal subtitle
5. **Write on device** — saves `MovieName.SubArabify.ar.srt` next to the video so MX Player, VLC, and similar apps can load it
6. **Preview in app** — tap any item's subtitles icon to see the result as normal subtitles

Videos where neither subtitles nor intelligible English speech are found stay
**pending** (the preview explains what to add).
Tiny promo files (e.g. YTS ads with only a few cues) are marked **weak source** —
put a full `Movie.en.srt` beside the video and tap **Redo**.

### Subtitle branding (honest rules)

| Where | What |
|---|---|
| **Start** | Short `— SubArabify —` cue, **only** if a real gap exists before cue #1 |
| **Middle** | Free — dialogue only, no watermark |
| **End** | Short `— SubArabify —` cue ~1.5 s after the last line |
| **Filename** | Always `*.SubArabify.ar.srt` |
| **Inside the file** | A hidden `NOTE` for anyone who opens the `.srt` in a text editor |
| **Timings** | Dialogue timecodes bit-identical to the source — sync never hurt |

---

## Features

| Feature | Reality |
|---|---|
| Offline translation | ML Kit EN→AR on device after the model downloads once |
| Offline speech-to-text | Vosk small-en & whisper.cpp transcribe videos with no subtitles (beta, English audio) |
| Local LLM Translation | 2-stage whisper.cpp + llama.cpp / Qwen 2.5 1.5B (GGUF Q4_K_M) batched translation pipeline |
| Colab T4 transcription | Optional free-GPU backend: full-movie **Arabic** ASR via `samil24/whisper-large-arabic-dialects-v5` — notebook + client in [`colab/`](colab/) |
| Translation memory | Repeated lines cached on disk across runs — no re-translate lag |
| In-app preview | Cues rendered like a real player, AR/EN toggle |
| Background scans | WorkManager at your interval (15 min – 6 hours) |
| Smart skip | Already has `.SubArabify.ar.srt` → skip; user skip list supported |
| Smart source | Fuzzy base match + any-language fallback + `.vtt` support |
| Jellyfin addon | Same engine on your server / Android Termux — see [`jellyfin-addon/`](jellyfin-addon/) |

## Jellyfin addon (server + Termux)

The addon does the same thing on your media server, natively — including on
Android via Termux (pure Python, no .NET):

```bash
# Termux
bash jellyfin-addon/run-termux.sh ~/storage/movies

# Normal server
pip install -r jellyfin-addon/requirements.txt
python jellyfin-addon/subarabify_jellyfin.py --media /media --watch
```

When enabled it **first downloads the small Arabic LLM** (`Helsinki-NLP/opus-mt-en-ar`,
~200 MB) and only then translates the current movies — writing
`Movie.SubArabify.ar.srt` with the app name and untouched timings.
Full docs: [`jellyfin-addon/README.md`](jellyfin-addon/README.md).

**Jellyfin → Repositories URL** (Dashboard → Plugins → Repositories → New Repository —
paste the `manifest.json` URL, not a GitHub page link):

```
https://muxd22-alt.github.io/SubArabify/jellyfin-manifest.json
```

Shows the SubArabify icon + 1.0.4-pre; enabling = running the companion above.

## Architecture

```
app/src/main/java/com/subarabify/
├── data/
│   ├── SrtParser.kt          # Parse (SRT+VTT) + timing-safe branded builder + AR post-fix
│   ├── TranslationMemory.kt  # Persistent EN→AR cache (kills re-translate lag)
│   ├── AudioExtractor.kt     # Video audio → 16 kHz mono PCM stream (MediaCodec, no FFmpeg)
│   └── StorageHelper.kt      # SAF helpers + smart/fuzzy source resolver
├── engine/
│   ├── MlKitTranslator.kt    # Chunked offline EN→AR (glossary → memory → model)
│   └── SttEngine.kt          # Offline English STT (Vosk) with word-timestamp cues
├── ui/
│   └── MainActivity.kt       # Folder pick, monitoring, model cards, subtitle preview
└── worker/
    └── SubArabifyWorker.kt   # Folder scan → translate / transcribe → write .srt + preview
jellyfin-addon/
├── subarabify_jellyfin.py    # Server/Termux addon (model download → scan → write, --stt fallback)
├── srtcore.py                # Same SRT rules as SrtParser.kt (parity)
├── stt.py                    # Server STT fallback (ffmpeg + faster-whisper)
├── whisper_cpp.py            # whisper.cpp engine wrapper (16kHz WAV -> JSON transcript)
├── llm_translator.py         # llama.cpp / Qwen 2.5 1.5B GGUF batch translation wrapper
└── manifest.json             # Jellyfin plugin-repository manifest (1.0.4-pre)
colab/
├── app.ipynb                 # Free-GPU backend: FastAPI + samil24 Arabic Whisper + tunnel (run in Colab)
├── client.py                 # Video → 16 kHz WAV → submit/poll/download → branded .srt (resume-capable)
├── test_client.py            # Self-tests against a mock backend; ffmpeg e2e when available
└── requirements.txt          # client deps (requests only)
```

## Building locally

```bash
gradle assembleRelease --no-daemon
```

APK output: `app/build/outputs/apk/release/`

## Branding / icon

The app icon, launcher icon, landing page, README header, and Jellyfin manifest
all use [`docs/icon.jpg`](docs/icon.jpg) — one look everywhere.

## License

Open-source. Privacy-first. On-device.
