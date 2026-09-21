# SubArabify Jellyfin addon — v1.0.3-beta

On-server Arabic subtitles for your whole Jellyfin library. Runs on a normal
media server **and** on Android via **Termux** (pure Python — no .NET needed).

![SubArabify icon](../docs/icon.jpg)

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

`manifest.json` in this folder describes the addon for a Jellyfin plugin
repository (v1.0.3-beta, icon = `docs/icon.jpg`). Because this addon is a
native Python companion (Termux-compatible), install it via the steps above
rather than as a `.dll` — the manifest carries the same id/version/changelog
so it shows up as one entry everywhere.

## Files

| File | Purpose |
|---|---|
| `subarabify_jellyfin.py` | the addon: model download → scan → translate → write |
| `srtcore.py` | SRT/VTT parse + timing-safe branding (parity with the Android `SrtParser`) |
| `requirements.txt` | transformers + torch CPU + helpers |
| `manifest.json` | Jellyfin plugin-repository manifest (1.0.3-beta) |
| `run-termux.sh` | Android Termux installer/runner |
| `subarabify.service` | systemd unit for normal servers |

## Options

```
--media DIR        media folder (repeatable)
--once             single scan then exit
--watch            rescan every --interval minutes
--interval N       watch interval in minutes (default 60)
--force            retranslate even if output exists
--jellyfin-refresh trigger Jellyfin library refresh (needs JELLYFIN_URL + JELLYFIN_API_KEY)
```

Env: `SUBARABIFY_MODEL` (default `Helsinki-NLP/opus-mt-en-ar`),
`SUBARABIFY_MEDIA_DIR`, `SUBARABIFY_CACHE`.
