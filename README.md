# SubArabify — v1.0.3-beta

![SubArabify icon](docs/icon.jpg)

**Automatic Arabic subtitles on your phone — offline, folder-first, privacy-first.**

Pick a media folder. SubArabify finds English `.srt`/`.vtt` files next to your videos,
translates them on-device, and writes a player-ready Arabic file beside each video —
with timings copied **bit-identical** and an in-app preview that looks like normal subtitles.

---

## Download

| | |
|---|---|
| **Latest APK** | [SubArabify-latest.apk](https://muxd22-alt.github.io/SubArabify/SubArabify-latest.apk) |
| **Landing page** | [muxd22-alt.github.io/SubArabify](https://muxd22-alt.github.io/SubArabify/) |
| **Source** | [github.com/muxd22-alt/SubArabify](https://github.com/muxd22-alt/SubArabify) |
| **Jellyfin addon** | [`jellyfin-addon/`](jellyfin-addon/) — same engine for your media server / Termux |

Built automatically on every push to `main` and published to GitHub Pages (same APK the site's Download button serves).

> Requires Android. Install from unknown sources / your file manager. Current builds are signed with the debug keystore (super-beta).

---

## What's smarter in 1.0.3-beta

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
| Offline speech-to-text | Vosk small-en transcribes videos with no subtitles (beta, English audio) |
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

Shows the SubArabify icon + 1.0.3-beta; enabling = running the companion above.

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
└── manifest.json             # Jellyfin plugin-repository manifest (1.0.3-beta)
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
