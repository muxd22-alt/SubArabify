# SubArabify Colab backend + client — v1.0.4-pre

Turn any local movie into an Arabic `.srt` using a **free Google Colab T4 GPU**.
Your machine only needs `ffmpeg` + Python; all heavy listening happens on Colab.

```
local video ──ffmpeg──▶ 16 kHz mono WAV ──upload──▶ Colab T4
   (Whisper large-v3 fine-tuned for Arabic dialects) ──▶ Arabic .srt
   ──srtcore──▶ Movie.SubArabify.ar.srt (next to the video)
```

## 1. Start the backend (Colab)

* Open the notebook: <https://colab.research.google.com/github/muxd22-alt/SubArabify/blob/main/colab/app.ipynb>
* **Runtime → Change runtime type → GPU (T4)** (the first cell asserts this)
* **Runtime → Run all**

The last cells print:

```
PUBLIC URL: https://xxxxxxx.trycloudflare.com
✔ tunnel OK — …
```

Copy that URL. **Keep the tab open while jobs run** — Colab disconnects idle sessions,
and every new session gets a brand-new URL.

> Model: [`samil24/whisper-large-arabic-dialects-v5`](https://huggingface.co/samil24/whisper-large-arabic-dialects-v5)
> (openai/whisper-large-v3 fine-tuned on Arabic dialects). It is a raw Hugging Face
> Transformers checkpoint (safetensors), so the notebook uses `transformers` (fp16) —
> **faster-whisper is not used** for this model (it only loads converted CTranslate2 weights).
> A 2-hour movie ≈ **15–20 minutes** of processing on a T4.

## 2. Transcribe from your machine

```bash
# deps: Python 3.8+, requests, ffmpeg on PATH
pip install -r colab/requirements.txt      # just requests

python colab/client.py "My Movie.mkv" --url https://xxxxxxx.trycloudflare.com
python colab/client.py a.mp4 b.mkv --url $URL --force        # batch, overwrite
SUBARABIFY_COLAB_URL=... python colab/client.py "Movie.mp4"  # URL via env
```

It prints live progress (`% · elapsed · eta~`), then writes
`Movie.SubArabify.ar.srt` **next to the video**, branded with the same rules as
the Android app and the Jellyfin addon (NOTE + brand cues only in free gaps,
dialogue timecodes untouched).

### Resume after a disconnect

Jobs live *while the Colab session lives*. If your network dies mid-movie
(or the script hits its default 90-minute deadline), it prints the exact resume
command — here it is again:

```bash
python colab/client.py --url https://xxxxxxx.trycloudflare.com \
       --job <job_id> --save-to "My Movie.mkv"
```

Polling alone resumes: no re-upload, no re-transcription. If the Colab session
itself restarted, the job is gone — re-submit (the client says so clearly).

## Client options

```
python colab/client.py [videos ...] [options]

--url URL        public tunnel URL (or $SUBARABIFY_COLAB_URL)
--job <id>       resume an existing job: poll + download only
--save-to PATH   video/anchor path used for output naming with --job
--sync           blocking /transcribe call — small clips only (see below)
--timeout N      overall deadline per job, seconds (default 5400 = 90 min)
--poll N         seconds between status polls (default 15)
--force          overwrite existing .SubArabify.ar.srt
--transport wav|opus|auto   auto = WAV ≤90 MB else Opus (default)
--keep-wav       keep the extracted 16 kHz WAV in a temp folder
```

Exit codes: `0` ok · `1` error · `2` timeout (job may still finish — resume).

### Why "transport auto" exists

Cloudflare quick tunnels reject request bodies near **100 MB**. A raw 2-hour WAV is
~230 MB, so for files over 90 MB the client transparently ships **16 kHz mono Opus**
(~20× smaller, speech-optimized — same decoding quality for Whisper). `--transport wav`
forces raw WAV when you're under the cap or use ngrok.

## Backend API (what the client talks to)

| Endpoint | Body | Returns |
|---|---|---|
| `GET /health` | – | model state, GPU, queue depth |
| `POST /jobs` | multipart `file=…` | `202 {"job_id": …}` |
| `POST /jobs/url` | `{"url": "https://…/audio.wav"}` | `202 {"job_id": …}` (server downloads it) |
| `GET /jobs/{id}` | – | `{"status", "progress", "message"}` |
| `GET /jobs/{id}/srt` | – | `.srt` text (`409` while processing) |
| `POST /transcribe` | multipart `file=…` | `.srt` text, blocking |

```bash
curl -F "file=@clip.wav" "$URL/transcribe"
curl -F "file=@movie.wav" "$URL/jobs"          # ─┐
curl "$URL/jobs/<id>"                           #  ├─ async movie flow
curl "$URL/jobs/<id>/srt"                       # ─┘
```

Jobs run one at a time (single T4); extra submissions queue. Finished jobs stay
downloadable for the session (last 8 kept).

## Files

| File | Purpose |
|---|---|
| `app.ipynb` | Colab notebook: env → model → FastAPI → tunnel → usage (run cells top-to-bottom) |
| `client.py` | the CLI above (ffmpeg extract → upload → poll → download → srtcore branding) |
| `test_client.py` | self-tests: pure units + mock backend + real ffmpeg e2e when available |
| `requirements.txt` | client deps (just `requests`) |

`client.py` reuses the shared [`jellyfin-addon/srtcore.py`](../jellyfin-addon/srtcore.py)
for SRT parsing, Arabic post-fix, and timing-safe branding, so output always matches
the app (parity guaranteed by `jellyfin-addon/test_srtcore.py`).

## Troubleshooting

* **`cannot reach the backend at …`** — URL is stale (each Colab session gets a new one) or
  the notebook stopped. Re-copy the `PUBLIC URL` line.
* **`resource is being used by another layer` / 502s** while polling — transient tunnel
  blips; the client retries ~20× before giving up (a flaky line drops to *n failures*).
* **Upload rejected (413)** — over the tunnel body cap; re-run with `--transport opus`
  (the default already handles it automatically).
* **Colab session died mid-job** — job lost (it lived in RAM); submit again. A fresh
  session needs a fresh URL *and* a fresh upload.
* **ngrok preferred** — set the Colab secret `NGROK_AUTHTOKEN`, re-run the tunnel cell;
  the client works with either URL unchanged.