# SubArabify client — v0.2.2-beta

Turns the Colab T4 backend (`backend/SubArabify_Backend.ipynb`) into Arabic
subtitles for your local movie folder. Pure routing — **no local AI**, just
ffmpeg + uploads.

## Install

```bash
pip install -r client/requirements.txt   # requests is the only dep
# ffmpeg on PATH:  winget install ffmpeg   |   sudo apt install ffmpeg
```

## Use

```bash
python client/client.py "Movie.mkv" --url https://xxxx.trycloudflare.com

# scan a whole library; each movie is routed automatically
python client/client.py --media ~/movies --media ~/tv --url $SUBARABIFY_COLAB_URL

# batch + force redo
python client/client.py a.mp4 b.mkv --url $URL --force
```

The URL comes from the Colab notebook (`backend/SubArabify_Backend.ipynb`) →
**PUBLIC URL** line. `GET /health` is checked on every start, so a stale URL
prints a clear error instead of a mystery.

## Per-movie routing

Each video gets exactly **one** pipeline — nothing runs locally:

| condition | action |
|---|---|
| `Name.SubArabify.ar.srt` exists | **skip** (already done; `--force` redoes) |
| English subtitle beside the video (`Name.en.srt` / `Name.srt` / fuzzy match) | upload the `.srt` as `srt=` → backend **translates to Arabic**, timings bit-identical |
| no subtitle at all | ffmpeg-extract `0:a:0` → 16 kHz mono WAV → upload as `file=` → backend **transcribes to Arabic** |

Promo/stub subtitle files (< 30 cues) are rejected (`weak_source`), and our own
outputs are never picked up as source files.

## Output

`Name.SubArabify.ar.srt` is written **next to the video**, branded by the
shared `srtcore` rules (— SubArabify —/NOTE, Arabic typography, bit-identical
timings, safe-gap brand cues) — identical to what the Android app produces.

## Long jobs & connectivity

Movies take ~15–20 min on the T4 and the API is **async** on purpose:

```bash
# run it, and if your laptop drops mid-job:
python client/client.py --url $URL --job <job_id> --save-to "Movie.mkv"
```

Polling tolerates transient tunnel blips; the overall deadline
(`-t/--timeout`, default 90 min) trips exit code **2** and prints the exact
`--job` resume command. Exit codes: **0** ok · **1** error · **2** timeout.

## Batch knobs

* `--sync` — single blocking `POST /transcribe`; short clips only (movies must
  use the async path).
* `--transport wav|opus|auto` — auto = raw WAV ≤ 90 MB else Opus (default:
  Cloudflare tunnels cap near 100 MB).
* `--keep-wav` — keep the extracted 16 kHz WAV (in a temp folder) for debug.
* `SUBARABIFY_COLAB_URL` env var can replace `--url`.

## Tests

```bash
python client/test_srtcore.py     # shared branding/parsing rules
python client/test_client.py      # routing + mock-backend e2e (ffmpeg optional)
```