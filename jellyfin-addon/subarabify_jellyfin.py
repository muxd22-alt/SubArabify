#!/usr/bin/env python3
"""SubArabify unified Jellyfin addon — v0.2.3-alpha.

The engine is the SAME Colab T4 backend the Android app and the Python client
use (backend/SubArabify_Backend.ipynb). This addon is just another front door:
it scans a media library on a Jellyfin server (or in Termux on an Android TV),
routes each movie exactly like the app, and writes the branded
``Movie.SubArabify.ar.srt`` next to the file.

Unified config — ONE colab URL everywhere:
  1. Android app     → Backend card (the same tunnel URL you paste below)
  2. Python client   → client/client.py --url $SUBARABIFY_COLAB_URL
  3. This addon      → config.json "colab_url"  (or --url / the env var)

Getting the URL from the server to the phone/TV without typing:
  * --qrcode  prints the configured URL as a QR code — scan it with any phone
              camera app, copy the text, and paste it into the Android app.
  * --serve   starts a tiny LAN endpoint on the local network:
                  GET http://<this-host>:<port>/subarabify/config
              open that with a phone browser to copy the URL with zero setup.

Usage:
  python jellyfin-addon/subarabify_jellyfin.py --media /media/movies
  python jellyfin-addon/subarabify_jellyfin.py --media /media/movies --watch
  python jellyfin-addon/subarabify_jellyfin.py --qrcode
  python jellyfin-addon/subarabify_jellyfin.py --serve --port 8477
  python jellyfin-addon/subarabify_jellyfin.py --url https://xxx.trycloudflare.com \
        --media /media/movies --force

Routing per movie (identical to the Android worker):
  * already has *.SubArabify.ar.srt  -> skip
  * has an English .srt/.vtt        -> upload the SUBTITLE, backend translates
  * nothing at all                  -> the addon uploads the audio/whole file,
                                        backend transcodes + transcribes to Arabic

The backend converts any uploaded file itself (its own ffmpeg), so for files
under the tunnel limit (~90 MB) NO ffmpeg is needed at all — the addon sends
the media file directly. Bigger files are reduced to a 16 kHz WAV (and to Opus
when that would still be huge) using a local ffmpeg if one is available
(Jellyfin ships one; Termux: ``pkg install ffmpeg``).
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import requests
except ImportError as e:  # pragma: no cover
    sys.exit("jellyfin-addon needs `requests` — pip install -r jellyfin-addon/requirements.txt")

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import srtcore  # noqa: E402  (identical copy of client/srtcore.py)

VERSION = "0.2.3-alpha"
CF_SAFE_BYTES = 90 * 1024 * 1024   # Cloudflare quick tunnel body cap
DEFAULT_TIMEOUT = 5400             # 90 min overall per job
POLL = 15.0
MAX_POLL_MISSES = 20
UPLOAD_WINDOW = 1800.0
MIN_CUES = 30
MIN_BYTES = 2000
DEFAULT_CONFIG = HERE / "config.json"
DEFAULT_PORT = 8477
SERVICE_NAME = "subarabify-jellyfin"

try:
    import qrcode  # noqa: E402  (optional — only needed for --qrcode PNG/ascii)
except ImportError:  # pragma: no cover
    qrcode = None

VIDEO_EXTS = srtcore.VIDEO_EXTS


class AddonError(RuntimeError):
    pass


# ── config: ONE colab URL for the whole unified setup ───────────────────

def load_config(path=None) -> dict:
    p = Path(path) if path else DEFAULT_CONFIG
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_config(cfg: dict, path=None) -> Path:
    p = Path(path) if path else DEFAULT_CONFIG
    p.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return p


def resolve_base(url_flag: str | None, config: dict) -> str:
    """Precedence: --url flag > env SUBARABIFY_COLAB_URL > config.json colab_url."""
    base = (url_flag or "").strip() or os.environ.get("SUBARABIFY_COLAB_URL", "").strip() \
        or str(config.get("colab_url", "")).strip()
    if not base:
        raise AddonError(
            "no backend URL. Set it in jellyfin-addon/config.json (\"colab_url\") "
            "or pass --url <public-url> / export SUBARABIFY_COLAB_URL.\n"
            "  Get the URL from the Colab notebook "
            "(backend/SubArabify_Backend.ipynb) → 'PUBLIC URL' line.\n"
            "  Tip: jellyfin-addon/subarabify_jellyfin.py --qrcode shows it on the TV.")
    return base.rstrip("/")


# ── QR code for the TV ──────────────────────────────────────────────────

def _lan_ip() -> str:
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:  # pragma: no cover
        return "127.0.0.1"


def render_qr(url: str):
    """Return the QR as a string (2-char-wide blocks) — or None without the lib."""
    if qrcode is None:
        return None
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=1)
    q.add_data(url)
    q.make(fit=True)
    block = "██"
    return "\n".join("".join(block if cell else "  " for cell in row)
                     for row in q.get_matrix())


def qr_banner(url: str, png: str | None = None) -> str:
    if qrcode is not None:
        art = render_qr(url) or ""
        if png:
            try:
                qrcode.make(url).save(png)
            except Exception:
                png = None
        extra = f"\n[png] saved: {png}" if png else ""
        return f"{art}{extra}\n[url] {url}\n[addon v{VERSION}] pasting this URL in the app Backend card is all you need."
    return (
        f"=== SubArabify {VERSION} — backend URL ===\n\n"
        f"    {url}\n\n"
        "To show this as a real scannable QR code on the TV:\n"
        "    pip install 'qrcode[pil]'   (one time, in this venv)\n"
        f"    python {Path(sys.argv[0]).name if sys.argv else 'subarabify_jellyfin.py'} --qrcode\n"
        "Or copy the URL above into the Android app → Backend card.")


# ── tiny LAN config endpoint (phone-friendly copy without typing) ───────

class _ConfigHandler(BaseHTTPRequestHandler):
    url = ""
    cfg = {}

    def log_message(self, *a):  # quiet
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.split("?")[0] in ("/subarabify/config", "/"):
            self._json({"service": SERVICE_NAME, "version": VERSION,
                        "colab_url": type(self).url, **type(self).cfg})
        else:
            self._json({"error": "not found"}, 404)


def start_config_server(base: str, cfg: dict, port: int):
    """Serve the colab URL + config JSON on the LAN. Returns (server, url)."""
    _ConfigHandler.url = base
    _ConfigHandler.cfg = {k: v for k, v in cfg.items() if k != "colab_url"}
    httpd = ThreadingHTTPServer(("0.0.0.0", port), _ConfigHandler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://{_lan_ip()}:{httpd.server_address[1]}/subarabify/config"


# ── backend client (mirrors client/client.py — same API, same tolerance) ──

def _deadline(steps_s: float, what: str, run):
    result, err = {}, {}

    def _target():
        try:
            result["v"] = run()
        except BaseException as e:  # noqa: BLE001
            err["e"] = e

    t = threading.Thread(target=_target, daemon=True)
    t.start()
    t.join(steps_s)
    if t.is_alive():
        raise AddonError(f"{what} still going after {steps_s:.0f}s — network stalled")
    if "e" in err:
        raise err["e"]
    return result.get("v")


def check_backend(base: str):
    try:
        r = requests.get(f"{base}/health", timeout=(10, 25))
        r.raise_for_status()
        j = r.json()
    except Exception as e:  # noqa: BLE001
        raise AddonError(
            f"cannot reach the backend at {base}: {e}\n"
            "  · is the Colab notebook still running? (backend/SubArabify_Backend.ipynb)\n"
            "  · the public URL changes every Colab session — update config.json / .env") from e
    print(f"[backend] {j.get('model')} · v{j.get('version')} · "
          f"model_loaded={j.get('model_loaded')} · mt_loaded={j.get('mt_loaded')}")


def submit_job(base: str, payload: Path, name: str, ctype: str, field: str) -> str:
    """POST /jobs — field "srt" → translate · field "file" → transcribe."""
    delays = (0, 5, 15)
    last = None
    for attempt, delay in enumerate(delays, 1):
        if delay:
            time.sleep(delay)

        def _do():
            with open(payload, "rb") as fh:
                return requests.post(f"{base}/jobs", files={field: (name, fh, ctype)},
                                     timeout=(30, 300))

        try:
            r = _deadline(UPLOAD_WINDOW, "upload", _do)
            if r.status_code in (413, 400):
                raise AddonError(f"upload rejected by backend ({r.status_code}): "
                                 f"{r.text[:200]} — the file may exceed the tunnel cap")
            r.raise_for_status()
            job_id = (r.json() or {}).get("job_id")
            if not job_id:
                raise AddonError(f"backend answered without a job_id: {r.text[:200]}")
            print(f"[sent] {name} → job {job_id} ({payload.stat().st_size/1048576:.1f} MB)")
            return job_id
        except AddonError:
            raise
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"\n[warn] upload attempt {attempt}/{len(delays)} failed: {e}", file=sys.stderr)
    raise AddonError(f"could not submit the job (last error: {last}).\n"
                     f"  Is '{base}' right and the Colab still running?")


def _fmt(s: float) -> str:
    s = int(s)
    h, rem = divmod(s, 3600)
    m, s = divmod(rem, 60)
    return (f"{h}h{m:02d}m" if h else (f"{m}m{s:02d}s" if m else f"{s}s"))


def wait_job(base: str, job_id: str, timeout_s: float, poll_s: float):
    t0 = time.time()
    misses = 0
    last_p = -1.0
    while True:
        elapsed = time.time() - t0
        if elapsed >= timeout_s:
            raise AddonError(
                f"timed out after {int(elapsed)}s — job {job_id} still running.\n"
                f"  The Colab backend keeps working; resume with:\n"
                f"    python {Path(sys.argv[0]).name} --url {base} --job {job_id} "
                "--save-to <video-path>")
        try:
            r = requests.get(f"{base}/jobs/{job_id}", timeout=(10, 30))
            if r.status_code == 404:
                raise AddonError(f"job {job_id} not found — Colab session restarted; re-submit")
            r.raise_for_status()
            st = r.json()
            misses = 0
        except AddonError:
            raise
        except Exception as e:  # noqa: BLE001
            misses += 1
            if misses >= MAX_POLL_MISSES:
                raise AddonError(
                    f"{misses} consecutive poll failures ({e}). The tunnel/Colab may be "
                    f"flaky — resume later with --job {job_id} --save-to <video-path>") from e
            print(f"\n[warn] poll failed ({misses}/{MAX_POLL_MISSES}): {e}", file=sys.stderr)
            time.sleep(poll_s)
            continue
        status = st.get("status")
        p = float(st.get("progress") or 0)
        if status == "done":
            print()
            return st
        if status == "error":
            raise AddonError(f"backend error for job {job_id}: {st.get('message')}")
        if p > 0.05 and abs(p - last_p) >= 0.005:
            eta = elapsed / p - elapsed
            line = (f"\r  [{p*100:5.1f}%] {st.get('message', '')} · "
                    f"elapsed {_fmt(elapsed)} · eta ~{_fmt(eta)}")
            print(f"{line[:120]:<120}", end="", flush=True)
            last_p = p
        time.sleep(poll_s)


def fetch_srt(base: str, job_id: str) -> str:
    last = None
    for attempt, delay in enumerate((0, 2, 5, 10, 15), 1):
        if delay:
            time.sleep(delay)
        try:
            r = requests.get(f"{base}/jobs/{job_id}/srt", timeout=(10, 60))
            r.raise_for_status()
            text = r.text
            if "-->" not in text:
                raise AddonError("backend response doesn't look like SRT")
            return text
        except AddonError:
            raise
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"\n[warn] srt download attempt {attempt}/5 failed: {e}", file=sys.stderr)
    raise AddonError(f"could not download the SRT: {last}")


def brand_and_write(srt_text: str, video: Path, force: bool) -> Path:
    blocks = srtcore.parse(srt_text)
    if not blocks:
        raise AddonError("backend returned an SRT that parses to zero cues")
    branded = srtcore.build_branded_srt(blocks, [b["lines"] for b in blocks])
    out = video.parent / f"{srtcore.smart_base(video.name)}.SubArabify.ar.srt"
    if out.exists() and not force:
        raise AddonError(f"{out.name} already exists (use --force to overwrite)")
    out.write_text(branded, encoding="utf-8")
    return out


# ── routing (identical decisions to the Android worker / client) ────────

def output_path(video: Path) -> Path:
    return video.parent / f"{srtcore.smart_base(video.name)}.SubArabify.ar.srt"


def existing_ar(folder: Path, base: str) -> Path | None:
    for cand in (f"{base}.SubArabify.ar.srt", f"{base}_SubArabify_ar.srt",
                 f"{base}.ar.srt"):
        p = folder / cand
        if p.exists():
            return p
    try:
        for p in folder.iterdir():
            if p.is_file() and p.name.startswith(base) and \
                    "subarabify" in p.name.lower() and p.suffix.lower() == ".srt":
                return p
    except OSError:
        return None
    return None


def find_source(folder: Path, video_base: str) -> Path | None:
    files = [p for p in folder.iterdir() if p.is_file()]
    by_name = {p.name: p for p in files}
    for cand in (f"{video_base}.en.srt", f"{video_base}.eng.srt",
                 f"{video_base}.English.srt", f"{video_base}.en-US.srt",
                 f"{video_base}.en-GB.srt", f"{video_base}.en.vtt",
                 f"{video_base}.eng.vtt"):
        p = by_name.get(cand)
        if p and not srtcore.is_ours(p.name):
            return p
    for cand in (f"{video_base}.srt", f"{video_base}.vtt"):
        p = by_name.get(cand)
        if p and not srtcore.is_ours(p.name):
            return p
    want = srtcore.fuzzy_key(video_base)
    fuzzy = [p for p in files
             if p.suffix.lower() in (".srt", ".vtt") and not srtcore.is_ours(p.name)
             and (srtcore.fuzzy_key(p.stem) == want
                  or srtcore.smart_base(p.stem).lower() == video_base.lower())]
    if fuzzy:
        def rank(p):
            l = p.name.lower()
            tag = 2 if (".en." in l or ".eng." in l or ".english." in l) else \
                (1 if l.endswith(".srt") else 0)
            return (tag, p.stat().st_size)
        return max(fuzzy, key=rank)
    words = {w for w in want.split() if len(w) > 2}
    if words:
        loose = [p for p in files
                 if p.suffix.lower() in (".srt", ".vtt") and not srtcore.is_ours(p.name)
                 and any(w in srtcore.fuzzy_key(p.stem) for w in words)]
        if loose:
            return max(loose, key=lambda p: p.stat().st_size)
    return None


def iter_videos(media_dirs):
    seen = set()
    for d in media_dirs:
        root = Path(d).expanduser()
        if not root.exists():
            print(f"[scan] missing dir: {root}", file=sys.stderr)
            continue
        for p in sorted(root.rglob("*")):
            if p.is_file() and p.suffix.lower().lstrip(".") in VIDEO_EXTS:
                key = str(p.resolve())
                if key not in seen:
                    seen.add(key)
                    yield p


def find_ffmpeg():
    cands = [
        shutil.which("ffmpeg") or "",
        "ffmpeg.exe",
        "/usr/bin/ffmpeg",
        "/usr/lib/jellyfin-ffmpeg/ffmpeg",
        "/usr/lib/jellyfin/bin/ffmpeg",
        "/data/data/com.termux/files/usr/bin/ffmpeg",
    ]
    for c in cands:
        if c and Path(c).exists():
            return c
    return None


def extract_wav(video: Path, tmpdir: Path, ffmpeg: str) -> Path:
    wav = tmpdir / "audio.wav"
    r = subprocess.run(
        [ffmpeg, "-nostdin", "-y", "-v", "error",
         "-i", str(video), "-map", "0:a:0",
         "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
        capture_output=True, text=True, timeout=7200)
    if r.returncode != 0 or not wav.exists() or wav.stat().st_size <= 44:
        raise AddonError(f"audio extraction failed: {r.stderr.strip()[:300]}")
    return wav


def compress_audio(wav: Path, tmpdir: Path, ffmpeg: str):
    ogg = tmpdir / "audio.opus.ogg"
    r = subprocess.run(
        [ffmpeg, "-nostdin", "-y", "-v", "error", "-i", str(wav),
         "-c:a", "libopus", "-b:a", "32k", "-ac", "1", "-ar", "16000", str(ogg)],
        capture_output=True, text=True, timeout=1800)
    if r.returncode == 0 and ogg.exists():
        return ogg, "audio.opus", "audio/ogg"
    return None


_MIME = {
    "mkv": "video/x-matroska", "mp4": "video/mp4", "avi": "video/x-msvideo",
    "mov": "video/quicktime", "m4v": "video/x-m4v", "wmv": "video/x-ms-wmv",
    "webm": "video/webm", "ts": "video/mp2t", "m2ts": "video/mp2t",
    "flv": "video/x-flv",
}


def pick_transcribe_payload(video: Path, tmpdir: Path):
    """The backend converts any file itself (its own ffmpeg). Smart order:
    1) file ≤ ~90 MB → send the movie/audio straight through — NO ffmpeg needed;
    2) bigger → 16 kHz WAV locally (needs a local ffmpeg), Opus if still huge.
    Returns (payload_path, filename, mime) or raises if no ffmpeg and file too big."""
    if video.stat().st_size <= CF_SAFE_BYTES:
        ext = video.suffix.lower().lstrip(".")
        mime = _MIME.get(ext, "application/octet-stream")
        print(f"[audio] {video.name} ({video.stat().st_size/1048576:.0f} MB ≤ 90) "
              "sent directly — backend converts it")
        return video, video.name, mime
    ff = find_ffmpeg()
    if ff is None:
        raise AddonError(
            f"{video.name} is {video.stat().st_size/1073741824:.1f} GB — over the ~90 MB "
            "tunnel cap. Install ffmpeg to extract the soundtrack first "
            f"(Jellyfin ships one; Termux: pkg install ffmpeg). Or use the container? No — "
            "install ffmpeg.")
    print(f"[audio] {video.name} → 16 kHz mono WAV → Arabic transcription …")
    wav = extract_wav(video, tmpdir, ff)
    if wav.stat().st_size <= CF_SAFE_BYTES:
        return wav, "audio.wav", "audio/wav"
    print(f"[audio] WAV is {wav.stat().st_size/1048576:.0f} MB (>90) — Opus for the tunnel")
    got = compress_audio(wav, tmpdir, ff)
    if got:
        return got
    warn = ("this ffmpeg lacks libopus — sending raw WAV (may fail past ~100 MB)")
    print(f"[warn] {warn}", file=sys.stderr)
    return wav, "audio.wav", "audio/wav"


# ── one movie through the pipeline (status tokens like the app) ─────────

def handle_video(video: Path, args, base: str) -> str:
    video = Path(video)
    if not video.exists():
        raise AddonError(f"video not found: {video}")
    folder, base_name = video.parent, video.stem

    if existing_ar(folder, base_name) and not args.force:
        print(f"[skip] {video.name}: Arabic output already exists (--force to redo)")
        return "done"

    src = find_source(folder, base_name)
    tmpdir = Path(tempfile.mkdtemp(prefix="subarabify-addon-"))
    try:
        if src is not None:
            return _translate(video, src, args, base, tmpdir)
        return _transcribe(video, args, base, tmpdir)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def _translate(video: Path, src: Path, args, base: str, tmpdir: Path) -> str:
    for enc in ("utf-8-sig", "cp1256", "latin-1"):
        try:
            raw = src.read_text(encoding=enc)
            break
        except Exception:
            continue
    else:
        print(f"[{video.stem}] read failed", file=sys.stderr)
        return "error"
    blocks = srtcore.parse(raw)
    if not blocks:
        print(f"[{video.stem}] {src.name} parses to zero cues — treating as no subtitle")
        return _transcribe(video, args, base, tmpdir)
    if len(blocks) < MIN_CUES or src.stat().st_size < MIN_BYTES:
        print(f"[{video.stem}] {src.name} looks like a promo stub "
              f"({len(blocks)} cues) — add a full .en.srt")
        return "weak_source"
    job_id = submit_job(base, src, src.name, "application/x-subrip", field="srt")
    print(f"[job ] {job_id} — translating {len(blocks)} cues on the backend "
          f"(poll every {args.poll:g}s, deadline {_fmt(args.timeout)}) …")
    wait_job(base, job_id, args.timeout, args.poll)
    srt_text = fetch_srt(base, job_id)
    written = brand_and_write(srt_text, video, args.force)
    print(f"[ok ] {video.name} → {written.name} (eng {src.name} → arabic)")
    return "translate_success"


def _transcribe(video: Path, args, base: str, tmpdir: Path) -> str:
    try:
        payload, name, ctype = pick_transcribe_payload(video, tmpdir)
    except AddonError as e:
        print(f"[{video.stem}] {e}", file=sys.stderr)
        return "error"
    job_id = submit_job(base, payload, name, ctype, field="file")
    print(f"[job ] {job_id} — polling every {args.poll:g}s, deadline {_fmt(args.timeout)} …")
    wait_job(base, job_id, args.timeout, args.poll)
    srt_text = fetch_srt(base, job_id)
    written = brand_and_write(srt_text, video, args.force)
    print(f"[ok ] {video.name} → {written.name} (transcription)")
    return "transcribe_success"


def resume_job(args, base: str) -> Path:
    if not args.save_to:
        raise AddonError("--job resume needs --save-to <video-path>")
    video = Path(args.save_to)
    print(f"[job ] resuming {args.job} …")
    wait_job(base, args.job, args.timeout, args.poll)
    srt_text = fetch_srt(base, args.job)
    written = brand_and_write(srt_text, video, args.force)
    print(f"[ok ] {written.name} ({written.parent})")
    return written


def run_scan(args, config, base, once=True):
    media = [m for m in (args.media or config.get("media") or []) if m]
    if not media:
        print("[scan] no media folders — pass --media /path/to/movies or set "
              "\"media\": [...] in config.json", file=sys.stderr)
        return 1
    while True:
        print(f"\n[scan] {time.strftime('%H:%M:%S')} — "
              f"{','.join(media)} · backend {base}")
        stats = {}
        for v in iter_videos(media):
            try:
                st = handle_video(v, args, base)
                stats[st] = stats.get(st, 0) + 1
            except (AddonError, requests.RequestException, OSError) as e:
                print(f"[fail] {v}: {e}", file=sys.stderr)
                stats["error"] = stats.get("error", 0) + 1
        if stats:
            print("[scan] " + ", ".join(f"{k}={v}" for k, v in stats.items()))
        interval = float(args.interval or config.get("watch_interval") or 300)
        if not args.watch:
            return 0
        print(f"[watch] sleeping {interval:g}s (Ctrl+C to stop) …")
        time.sleep(interval)


def run_config_server(args, config, base, noconfig_ok=False):
    port = int(args.port or config.get("port") or DEFAULT_PORT)
    httpd, url = start_config_server(base, config, port)
    print(f"[serve] LAN config endpoint: {url}")
    print(f"[serve] open it with a phone browser to copy the colab URL — "
          f"paste that same URL into the Android app Backend card")
    print(f"[serve] Ctrl+C to stop")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.shutdown()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="jellyfin-addon/subarabify_jellyfin.py",
        description="SubArabify unified Jellyfin addon v0.2.3-alpha — backend-driven. "
                    "SAME Colab URL as the Android app; QR ($--qrcode) on the TV; "
                    "Termux runner; LAN config endpoint ($--serve).",
        epilog=(
            "config precedence: --url > $SUBARABIFY_COLAB_URL > config.json colab_url\n"
            "examples:\n"
            "  python jellyfin-addon/subarabify_jellyfin.py --media /media/movies --watch\n"
            "  python jellyfin-addon/subarabify_jellyfin.py --qrcode\n"
            "  python jellyfin-addon/subarabify_jellyfin.py --serve --port 8477\n"
            "  ./jellyfin-addon/run-termux.sh --media ~/storage/movies --watch\n"
            "routing: existing Arabic → skip · English .srt → translate · none → transcribe"))
    ap.add_argument("--media", action="append", default=[],
                    help="media folder to scan recursively (repeatable; or config.json media)")
    ap.add_argument("--watch", action="store_true",
                    help="keep scanning every --interval seconds")
    ap.add_argument("--interval", type=float, default=None,
                    help="watch interval in seconds (default 300)")
    ap.add_argument("--url", help="colab backend URL (also $SUBARABIFY_COLAB_URL / config.json)")
    ap.add_argument("--config", default=str(DEFAULT_CONFIG),
                    help="path to config.json (default jellyfin-addon/config.json)")
    ap.add_argument("--qrcode", action="store_true",
                    help="print the configured backend URL as a scannable QR (TV-friendly)")
    ap.add_argument("--qr-png", help="also save the QR as a PNG image file")
    ap.add_argument("--serve", action="store_true",
                    help="serve the colab URL + config on the LAN (phone-safe copy)")
    ap.add_argument("--port", type=int, default=None, help="port for --serve (default 8477)")
    ap.add_argument("--job", help="resume an existing job id: poll + download only")
    ap.add_argument("--save-to", help="video path used for output naming with --job")
    ap.add_argument("--force", action="store_true",
                    help="overwrite existing .SubArabify.ar.srt")
    ap.add_argument("--poll", type=float, default=POLL,
                    help=f"seconds between status polls (default {POLL:g})")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                    help="overall deadline in seconds per job (default 5400)")
    ap.add_argument("--version", action="version", version=f"SubArabify {VERSION} addon")
    args = ap.parse_args(argv)

    config = load_config(args.config)
    try:
        base = resolve_base(args.url, config)
    except AddonError:
        if args.qrcode:
            print(qr_banner("", None))
            return 1
        raise

    if args.qrcode:
        print(qr_banner(base, args.qr_png))
        return 0
    if args.serve:
        run_config_server(args, config, base)
        return 0

    if args.job:
        check_backend(base)
        resume_job(args, base)
        return 0
    if not args.media:
        config_media = config.get("media") or []
        if not config_media:
            ap.error("give --media <dir>, or add \"media\" to config.json, or use "
                     "--qrcode / --serve / --job")
        args.media = config_media
    check_backend(base)
    return run_scan(args, config, base)


if __name__ == "__main__":
    sys.exit(main())