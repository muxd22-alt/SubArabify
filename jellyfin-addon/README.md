# SubArabify Jellyfin addon — v1.0.4-pre

On-server Arabic subtitles for your whole Jellyfin library. Runs on a normal
media server **and** on Android via **Termux** (pure Python — no .NET needed).

![SubArabify icon](../docs/icon.jpg)

## Add it to Jellyfin → Repositories (correct URL)

Jellyfin **Repositories** only accept a `manifest.json` URL — a
`github.com/.../tree/...` page link will never load. Paste exactly this
(Dashboard → Plugins → Repositories → New Repository):

```
https://muxd22-alt.github.io/SubArabify/jellyfin-manifest.json
```

You'll see **SubArabify Arabic Subtitles 1.0.4-pre with the app icon** and
version info. Then **enable it by running the companion** (one command below) —
Jellyfin's catalog can only Install `.NET` plugins, and this addon is a native
Python companion (that's what makes Termux possible), so the repository entry
is for discovery/version while enabling happens on the server.

## What happens when you enable it

1. **First downloads the small Arabic model once** — `Helsinki-NLP/opus-mt-en-ar`
   (~200 MB, CPU-friendly). Nothing is translated before the model is ready.
2. **Scans your media folders** for movies/episodes.
3. **Finds the English subtitle** — exact (`.en.srt`) → fuzzy
   (`Movie.2023.1080p.mkv` ↔ `movie_en.srt`) → any-language fallback → `.vtt`.
4. **Translates offline** in context chunks with a persistent translation memory
   (`glossary → memory → model`), so re-runs have no lag.
5. **Writes `Movie.SubArabify.ar.srt`** next to the video — dialogue timecodes
   copied **bit-identical**, brand cues (`— SubArabify —`) only in free gaps,
   so sync is never hurt. Middle stays free, exactly like the Android app.

## Termux (Android) — native

```bash
pkg install python git
git clone https://github.com/muxd22-alt/SubArabify
cd SubArabify/jellyfin-addon
bash run-termux.sh ~/storage/movies     # downloads model, single scan
python subarabify_jellyfin.py --media ~/storage/movies --watch --interval 60
```

Allow Termux storage first (`termux-setup-storage`), then point `--media` at it.

## Normal server

```bash
pip install -r requirements.txt
python subarabify_jellyfin.py --media /media/movies --media /media/shows --once
# continuous + Jellyfin library refresh:
JELLYFIN_URL=http://localhost:8096 JELLYFIN_API_KEY=xxx \
  python subarabify_jellyfin.py --media /media --watch --jellyfin-refresh
```

Systemd: copy `subarabify.service` to `/etc/systemd/system/`, edit the
`/media` path + API key, then `systemctl enable --now subarabify`.

## Jellyfin plugin repository

`manifest.json` in this folder is the Jellyfin repository manifest template
(array format, stable guid, `imageUrl` = the SubArabify icon, version 1.0.4-pre).
CI fills in the real zip checksum + timestamp and publishes it at
`https://muxd22-alt.github.io/SubArabify/jellyfin-manifest.json` — that is the
URL to paste into Jellyfin → Repositories. Because this addon is a native
Python companion (Termux-compatible, not a `.dll`), the repository entry gives
you icon + version/discovery while enabling = running the companion above.

## Files

| File | Purpose |
|---|---|
| `subarabify_jellyfin.py` | the addon: model download → scan → translate → write (`--stt` = transcribe when no subs) |
| `srtcore.py` | SRT/VTT parse + timing-safe branding (parity with the Android `SrtParser`) |
| `stt.py` | offline STT fallback (ffmpeg + faster-whisper, word-timestamp cues) |
| `whisper_cpp.py` | whisper.cpp wrapper (16kHz WAV extraction -> `whisper-cli` JSON transcription) |
| `llm_translator.py` | llama.cpp / Qwen 2.5 1.5B (GGUF Q4_K_M) batch subtitle translation module |
| `requirements.txt` | transformers + torch CPU + helpers |
| `manifest.json` | Jellyfin plugin-repository manifest (1.0.4-pre) |
| `run-termux.sh` | Android Termux installer/runner |
| `subarabify.service` | systemd unit for normal servers |

## Options

```
--media DIR        media folder (repeatable)
--once             single scan then exit
--watch            rescan every --interval minutes
--interval N       watch interval in minutes (default 60)
--force            retranslate even if output exists
--stt              transcribe English audio offline (faster-whisper) when no
                   subtitle file exists — WhisperSubs-style fallback
--jellyfin-refresh trigger Jellyfin library refresh (needs JELLYFIN_URL + JELLYFIN_API_KEY)
```

Env: `SUBARABIFY_MODEL` (default `Helsinki-NLP/opus-mt-en-ar`),
`SUBARABIFY_MEDIA_DIR`, `SUBARABIFY_CACHE`, `SUBARABIFY_STT=1` (same as `--stt`),
`SUBARABIFY_STT_MODEL` (default `tiny.en`; `base.en`/`small.en` = slower, more accurate).

## Speech-to-text fallback (WhisperSubs-style)

No subtitle file at all? With `--stt`, the addon extracts the audio track
(ffmpeg — Jellyfin's bundled one at `/usr/lib/jellyfin-ffmpeg/ffmpeg` is used
first) and transcribes **English** speech locally with faster-whisper
(`tiny.en`, CPU int8, downloaded once into `<cache>/models-stt`). Word
timestamps become real cue timings, then the normal EN→AR translation runs and
the same branded `*.SubArabify.ar.srt` is written. Videos where almost nothing
is heard stay `pending`. Like WhisperSubs: self-hosted, repeat runs skip
unchanged items (translation memory + existing-output check), media never
leaves your server. Non-English audio is not transcribed well yet (tiny.en is
English-only) — that case stays `pending`.
