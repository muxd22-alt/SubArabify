# SubArabify — unified Jellyfin addon (v0.2.3-alpha)

The **same engine** as the Android app: a free Google Colab T4 backend. This
addon is just another front door for it — a Python companion that runs on a
Jellyfin *server*, in **Termux** on an Android TV/phone, or as a plain script
on any machine. It scans your library, routes each movie exactly like the app,
and writes `Movie.SubArabify.ar.srt` next to the file.

> Jellyfin can only *install* .NET plugins, so this addon ships as a companion:
> the repository entry in `jellyfin-manifest.json` carries the catalog card
> (icon + version + checksum); actual installation is the one-liner below. The
> Android Termux runner is the "smart" route — no .NET anywhere.

## One URL, everywhere

| Where | How the same tunnel URL gets set |
|---|---|
| **Android app** | App → **Backend card** → paste the Colab URL → Save |
| **Python client** | `client/client.py --url $SUBARABIFY_COLAB_URL` |
| **This addon** | `config.json` → `"colab_url"` (or `--url` / the env var) |

Precedence for the addon: `--url` flag **>** `$SUBARABIFY_COLAB_URL` **>**
`config.json` → `"colab_url"`.

If the URL is still empty, every run reminds you how to fill it.

## Quick start

```bash
# 1) configure the URL once
cp jellyfin-addon/config.json ~/.config/subarabify.json   # optional location
$EDITOR jellyfin-addon/config.json     # set "colab_url": "https://…trycloudflare.com"

# 2) install deps (requests; qrcode optional for the QR)
pip install -r jellyfin-addon/requirements.txt

# 3) scan your library once
python jellyfin-addon/subarabify_jellyfin.py --media /media/movies

#    …or keep watching it
python jellyfin-addon/subarabify_jellyfin.py --media /media/movies --watch --interval 600
```

## Getting the URL from the server to the phone / TV

You never have to type the long tunnel URL on a TV's on-screen keyboard.

**Option A — QR code right on the screen (Android TV / Termux):**

```bash
python jellyfin-addon/subarabify_jellyfin.py --qrcode
python jellyfin-addon/subarabify_jellyfin.py --qrcode --qr-png subarabify-qrcode.png
```

It prints a scannable QR of the configured backend URL. Scan it with any phone
camera app → you get the URL as *text* → paste it into the Android app's
**Backend card** (works on Android TV too: read the QR off the phone then paste
on the TV, or simply store it in the same config).

**Option B — a LAN endpoint to copy from (server machine / any HTTP client):**

```bash
python jellyfin-addon/subarabify_jellyfin.py --serve --port 8477
```

Open `http://<this-host>:8477/subarabify/config` in a phone browser: it returns
JSON with `colab_url` and the rest of the config — zero terminal typing.

## Termux universal runner (Android phone *and* Android TV)

```bash
pkg install -y python ffmpeg git
git clone https://github.com/muxd22-alt/SubArabify.git
cd SubArabify
./jellyfin-addon/run-termux.sh --media ~/storage/movies --watch
```

The script installs the deps, prompts once for the Colab URL, saves it into
`jellyfin-addon/config.json`, and hands off to the addon. Since it shares that
`config.json` with every other component, the URL is set **once**.

On an Android TV run the same in Termux; `--qrcode` prints the QR straight onto
the TV. (For a big screen, pair a phone/tablet as an SSH keyboard, or run
`--serve` and use a TV web browser.)

## Jellyfin server (repository manifest)

The repo publishes the addon ZIP + checksummed manifest:

```
Repository URL: https://muxd22-alt.github.io/SubArabify/jellyfin-manifest.json
Package:        https://muxd22-alt.github.io/SubArabify/subarabify-jellyfin-addon-0.2.3-alpha.zip
```

Jellyfin → Dashboard → Plugins → Repositories → add the manifest URL to see the
SubArabify card. Installation/enabling happens through the companion commands
above (Jellyfin cannot install dynamic plugins).

## What the addon does per movie

Identical routing to the Android worker and the Python client (`srtcore.py` is
a byte-identical copy of `client/srtcore.py`):

1. `Movie.SubArabify.ar.srt` already there → **skip** (`--force` redoes).
2. English `.srt`/`.vtt` next to it → upload the *subtitle* (`srt=` field) →
   backend **translates** EN→AR, timings bit-identical.
3. Nothing → upload the movie/audio itself (`file=` field) → backend's own
   ffmpeg converts it and **transcribes** straight into Arabic
   (Whisper large-v3 fine-tune for Arabic dialects).

Smart transport decision (no ffmpeg needed for most files):
- file **≤ ~90 MB** → sent **directly** (backend converts with its own ffmpeg);
- **bigger** → reduced to a 16 kHz WAV (needs a local ffmpeg; Jellyfin ships
  one at `/usr/lib/jellyfin-ffmpeg/ffmpeg`, Termux: `pkg install ffmpeg`);
- WAV still **> ~90 MB** → compressed to Opus for the tunnel.

## Resuming, timeouts, progress

Same async API as everything else: `POST /jobs` → `GET /jobs/{id}` → `GET
/jobs/{id}/srt`. Polling tolerates tunnel blips; a 90-minute deadline prints an
exact resume command:

```bash
python jellyfin-addon/subarabify_jellyfin.py --url $SUBARABIFY_COLAB_URL \
    --job <id> --save-to /path/to/Movie.mkv
```

## Tests

```bash
python jellyfin-addon/test_addon.py        # config, QR, LAN endpoint, routing, mock backend
python client/test_srtcore.py              # shared srtcore parity
python client/test_client.py               # client routing/backend (same engine)
```

## Files

| File | Purpose |
|---|---|
| `subarabify_jellyfin.py` | the addon (scan/watch, QR, serve, resume) |
| `srtcore.py` | identical copy of `client/srtcore.py` (parity, never drifts) |
| `config.json` | the one config: `colab_url`, `media`, intervals, port |
| `manifest.json` | repository catalog template (checksum injected by CI) |
| `make_manifest.py` | computes the zip SHA-256 → `public/jellyfin-manifest.json` |
| `requirements.txt` | `requests` (+ optional `qrcode[pil]`) |
| `run-termux.sh` | Termux universal runner (phone + Android TV) |
| `test_addon.py` | self-tests run in CI |