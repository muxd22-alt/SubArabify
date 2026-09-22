#!/usr/bin/env python3
"""SubArabify Colab client — v1.0.4-pre.

Pipeline for one or more local videos:
  video (.mp4/.mkv/…) ──ffmpeg──▶ 16 kHz mono WAV ──▶ Colab backend
  (colab/app.ipynb, free T4) ──Whisper Arabic──▶ .srt ──srtcore──▶
  Movie.SubArabify.ar.srt next to the video.

Usage:
  python colab/client.py "Movie.mkv" --url https://xxxx.trycloudflare.com
  python colab/client.py Movie1.mkv Movie2.mp4 --url $URL        # batch
  python colab/client.py --url $URL --job <id> --save-to "Movie.mkv"  # resume

The API is asynchronous on purpose (a 2 h movie takes ~15-20 min on the T4 —
far beyond any single HTTP request's comfort zone). If your connection dies
mid-job, re-run with `--job <id>` to resume polling + download.

Transient tunnel failures during polling are tolerated automatically. The
overall deadline (-t/--timeout, default 90 min) trips an exit code 2 and
prints the exact --job resume command.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

try:
    import requests
except ImportError as e:  # pragma: no cover
    sys.exit("colab/client.py needs `requests` — pip install requests")

# Windows consoles default to cp1252 and crash on →/…/·; degrade gracefully everywhere.
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "jellyfin-addon"))
import srtcore  # noqa: E402  (shared branding/parsing, stdlib-only)

CF_SAFE_BYTES = 90 * 1024 * 1024   # Cloudflare quick tunnels cap bodies near 100 MB
DEFAULT_TIMEOUT = 5400             # 90 min overall per job
POLL = 15.0                        # seconds between status polls
MAX_POLL_MISSES = 20               # ~5 min of consecutive tunnel failures -> abort
UPLOAD_WINDOW = 1800.0             # watchdog cap for a single upload request

VIDEO_EXTS = srtcore.VIDEO_EXTS


class ClientError(RuntimeError):
    """Fatal client-side problem (bad URL, ffmpeg, backend error…)."""


class JobTimeout(ClientError):
    """Overall --timeout elapsed while the job is still running."""


def find_ffmpeg():
    cands = [
        shutil.which("ffmpeg") or "",
        "ffmpeg.exe",
        "/usr/bin/ffmpeg",
        "/usr/lib/jellyfin-ffmpeg/ffmpeg",
        "/data/data/com.termux/files/usr/bin/ffmpeg",
    ]
    for c in cands:
        if c and Path(c).exists():
            return c
    raise ClientError(
        "ffmpeg not found on PATH.\n"
        "  · Windows:  winget install ffmpeg   (or https://ffmpeg.org)\n"
        "  · Debian/Ubuntu:  sudo apt install ffmpeg\n"
        "  · Termux:  pkg install ffmpeg")


def probe_duration_s(ffmpeg: str, video: Path):
    ffprobe = shutil.which("ffprobe")
    if not ffprobe and ffmpeg:
        probe = Path(ffmpeg).parent / ("ffprobe.exe" if os.name == "nt" else "ffprobe")
        if probe.exists():
            ffprobe = str(probe)
    if not ffprobe:
        return None
    try:
        r = subprocess.run(
            [ffprobe, "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
            capture_output=True, text=True, timeout=30)
        return float(r.stdout.strip()) if r.returncode == 0 else None
    except Exception:
        return None


def extract_wav(video: Path, tmpdir: Path, ffmpeg: str) -> Path:
    """Same recipe as jellyfin-addon/stt.py: first audio stream -> 16 kHz
    mono 16-bit PCM WAV (Whisper's native input)."""
    wav = tmpdir / "audio.wav"
    dur = probe_duration_s(ffmpeg, video)
    timeout = min(7200, max(600, (dur or 3600) * 2 + 300))
    r = subprocess.run(
        [ffmpeg, "-nostdin", "-y", "-v", "error",
         "-i", str(video), "-map", "0:a:0",
         "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
        capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0 or not wav.exists() or wav.stat().st_size <= 44:
        raise ClientError(f"audio extraction failed: {r.stderr.strip()[:300]}")
    return wav


def decide_transport(size_bytes: int, mode: str) -> str:
    """Pure: pick how to ship audio. Raw WAV under ~90 MB; bigger -> Opus,
    because Cloudflare quick tunnels reject ~100 MB request bodies."""
    if mode in ("wav", "opus"):
        return mode
    return "wav" if size_bytes <= CF_SAFE_BYTES else "opus"


def compress_audio(wav: Path, tmpdir: Path, ffmpeg: str):
    """16 kHz mono Opus in an Ogg container (~20x smaller for speech).
    Returns (path, name, mime) or None if the local ffmpeg lacks both
    encoders (the caller then falls back to raw WAV)."""
    ogg = tmpdir / "audio.opus.ogg"
    r = subprocess.run(
        [ffmpeg, "-nostdin", "-y", "-v", "error", "-i", str(wav),
         "-c:a", "libopus", "-b:a", "32k", "-ac", "1", "-ar", "16000", str(ogg)],
        capture_output=True, text=True, timeout=1800)
    if r.returncode == 0 and ogg.exists():
        return ogg, "audio.opus", "audio/ogg"
    mp3 = tmpdir / "audio.mp3"
    r = subprocess.run(
        [ffmpeg, "-nostdin", "-y", "-v", "error", "-i", str(wav),
         "-c:a", "libmp3lame", "-b:a", "48k", "-ac", "1", "-ar", "16000", str(mp3)],
        capture_output=True, text=True, timeout=1800)
    if r.returncode == 0 and mp3.exists():
        return mp3, "audio.mp3", "audio/mpeg"
    return None


def pick_payload(wav: Path, tmpdir: Path, ffmpeg: str, transport: str):
    mode = decide_transport(wav.stat().st_size, transport)
    if mode == "opus":
        mb = wav.stat().st_size / (1024 * 1024)
        print(f"[info] WAV is {mb:.0f} MB (>90). Compressing to Opus for the "
              f"tunnel (Cloudflare quick-tunnel limit). --transport wav to force raw.")
        got = compress_audio(wav, tmpdir, ffmpeg)
        if got:
            return got
        print("[warn] this ffmpeg has neither libopus nor libmp3lame — sending "
              "raw WAV (works only under ~100 MB)", file=sys.stderr)
    return wav, "audio.wav", "audio/wav"


def _deadline(steps_s: float, what: str, run):
    """Run `run` with an absolute time cap (requests has no send timeout)."""
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
        raise ClientError(f"{what} still going after {steps_s:.0f}s — network stalled "
                          "(re-run with --job <id> to resume, or retry).")
    if "e" in err:
        raise err["e"]
    return result.get("v")


def _check_backend(base: str):
    try:
        r = requests.get(f"{base}/health", timeout=(10, 25))
        r.raise_for_status()
        j = r.json()
        print(f"[backend] {j.get('model')} · v{j.get('version')} "
              f"· gpu={j.get('device')} · model_loaded={j.get('model_loaded')}")
    except Exception as e:  # noqa: BLE001
        raise ClientError(
            f"cannot reach the backend at {base}: {e}\n"
            "  · is the Colab notebook still running? (Runtime → …)\n"
            "  · the public URL changes every Colab session — copy the newest one\n"
            "  · inside the notebook:  %cat /content/subarabify_url.txt") from e


def submit_file(base: str, payload: Path, name: str, ctype: str) -> str:
    delays = (0, 5, 15)
    last = None
    for attempt, delay in enumerate(delays, 1):
        if delay:
            time.sleep(delay)

        def _do():
            with open(payload, "rb") as fh:
                return requests.post(
                    f"{base}/jobs", files={"file": (name, fh, ctype)},
                    timeout=(30, 300))

        try:
            r = _deadline(UPLOAD_WINDOW, "upload", _do)
            if r.status_code in (413, 400):
                raise ClientError(f"upload rejected by backend ({r.status_code}): "
                                  f"{r.text[:200]} — try --transport opus")
            r.raise_for_status()
            job_id = r.json().get("job_id")
            if not job_id:
                raise ClientError(f"backend answered without a job_id: {r.text[:200]}")
            print(f"[sent] {name} → job {job_id} ({payload.stat().st_size/1048576:.1f} MB)")
            return job_id
        except ClientError:
            raise
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"\n[warn] upload attempt {attempt}/{len(delays)} failed: {e}",
                  file=sys.stderr)
    raise ClientError(f"could not submit the job (last error: {last}).\n"
                      f"  Is '{base}' right and the Colab still running?")


def _fmt(s: float) -> str:
    s = int(s)
    h, rem = divmod(s, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{h}h{m:02d}m"
    if m:
        return f"{m}m{s:02d}s"
    return f"{s}s"


def wait_job(base: str, job_id: str, timeout_s: float, poll_s: float):
    """Poll until done/error. Tolerates transient tunnel failures; enforces
    the overall deadline and raises JobTimeout with a resume command."""
    script = Path(sys.argv[0]).name
    t0 = time.time()
    misses = 0
    last_p = -1.0
    while True:
        elapsed = time.time() - t0
        if elapsed >= timeout_s:
            raise JobTimeout(
                f"timed out after {int(elapsed)}s — job {job_id} still running.\n"
                "  The Colab backend keeps transcribing; resume any time with:\n"
                f"    python {script} --url {base} --job {job_id} "
                "--save-to <your-video-path>")

        try:
            r = requests.get(f"{base}/jobs/{job_id}", timeout=(10, 30))
            if r.status_code == 404:
                raise ClientError(f"job {job_id} not found — Colab session restarted "
                                  "(jobs live only for the current session); re-upload.")
            r.raise_for_status()
            st = r.json()
            misses = 0
        except ClientError:
            raise
        except Exception as e:  # noqa: BLE001
            misses += 1
            if misses >= MAX_POLL_MISSES:
                raise ClientError(
                    f"{misses} consecutive poll failures ({e}). The tunnel/Colab may "
                    f"be flaky — resume later with: python {script} --url {base} "
                    f"--job {job_id} --save-to <your-video-path>") from e
            print(f"\n[warn] poll failed ({misses}/{MAX_POLL_MISSES}): {e}",
                  file=sys.stderr)
            time.sleep(poll_s)
            continue

        status = st.get("status")
        p = float(st.get("progress") or 0)
        if status == "done":
            print()
            return st
        if status == "error":
            raise ClientError(f"backend error for job {job_id}: {st.get('message')}")
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
                raise ClientError("backend response doesn't look like SRT")
            return text
        except ClientError:
            raise
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"\n[warn] srt download attempt {attempt}/5 failed: {e}",
                  file=sys.stderr)
    raise ClientError(f"could not download the SRT: {last}")


def transcribe_sync(base: str, payload: Path, name: str, ctype: str, timeout_s: float) -> str:
    """Blocking POST /transcribe for short clips (movies should use async jobs)."""

    def _do():
        with open(payload, "rb") as fh:
            return requests.post(
                f"{base}/transcribe", files={"file": (name, fh, ctype)},
                timeout=(30, timeout_s + 60))

    try:
        r = _deadline(timeout_s + 120, "sync transcription", _do)
        r.raise_for_status()
        text = r.text
        if "-->" not in text:
            raise ClientError("backend response doesn't look like SRT")
        return text
    except requests.exceptions.ReadTimeout as e:
        raise JobTimeout(
            f"sync transcription exceeded {timeout_s}s — results lost on a long "
            "request. Re-run WITHOUT --sync (async job + resume) for movies.") from e


def brand_and_write(srt_text: str, video: Path, force: bool) -> Path:
    """Parse the backend SRT, apply the standard branding via srtcore
    (same rules as the app + Jellyfin addon), write next to the video."""
    blocks = srtcore.parse(srt_text)
    if not blocks:
        raise ClientError("backend returned an SRT that parses to zero cues")
    branded = srtcore.build_branded_srt(blocks, [b["lines"] for b in blocks])
    out = video.parent / f"{srtcore.smart_base(video.name)}.SubArabify.ar.srt"
    if out.exists() and not force:
        raise ClientError(f"{out.name} already exists (use --force to overwrite)")
    out.write_text(branded, encoding="utf-8")
    return out


def resolve_base(args) -> str:
    base = (getattr(args, "url", None) or os.environ.get("SUBARABIFY_COLAB_URL", "")).strip()
    if not base:
        raise ClientError(
            "no backend URL. Pass --url <public-url> or set SUBARABIFY_COLAB_URL.\n"
            "  Get the URL from the Colab notebook (colab/app.ipynb) → 'PUBLIC URL' line.")
    return base.rstrip("/")


def process_video(video: Path, args, base: str) -> Path:
    video = Path(video)
    if not video.exists():
        raise ClientError(f"video not found: {video}")
    out = video.parent / f"{srtcore.smart_base(video.name)}.SubArabify.ar.srt"
    if out.exists() and not args.force:
        print(f"[skip] {video.name}: {out.name} already exists (--force to redo)")
        return out

    ffmpeg = find_ffmpeg()
    tmpdir = Path(tempfile.mkdtemp(prefix="subarabify-colab-"))
    try:
        print(f"[audio] {video.name} → 16 kHz mono WAV …")
        wav = extract_wav(video, tmpdir, ffmpeg)
        payload, name, ctype = pick_payload(wav, tmpdir, ffmpeg, args.transport)

        if args.sync:
            print(f"[sync] POST /transcribe ({name}) — small clips only …")
            srt_text = transcribe_sync(base, payload, name, ctype, args.timeout)
        else:
            job_id = submit_file(base, payload, name, ctype)
            print(f"[job ] {job_id} — polling every {args.poll:g}s, deadline "
                  f"{_fmt(args.timeout)} …")
            wait_job(base, job_id, args.timeout, args.poll)
            srt_text = fetch_srt(base, job_id)

        written = brand_and_write(srt_text, video, args.force)
        print(f"[ok ] {video.name} → {written.name}")
        return written
    finally:
        if args.keep_wav:
            keep = tmpdir / "audio.wav"
            if keep.exists():
                print(f"[keep] {keep}")
        else:
            shutil.rmtree(tmpdir, ignore_errors=True)


def resume_job(args, base: str) -> Path:
    if not args.save_to:
        raise ClientError("--job resume needs --save-to <video-path> for the output "
                          "name/location (or pass the video as a positional argument)")
    video = Path(args.save_to)
    job_id = args.job
    print(f"[job ] resuming {job_id} …")
    wait_job(base, job_id, args.timeout, args.poll)
    srt_text = fetch_srt(base, job_id)
    written = brand_and_write(srt_text, video, args.force)
    print(f"[ok ] {written.name} ({written.parent})")
    return written


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="colab/client.py",
        description="SubArabify Colab client — ffmpeg audio → Colab T4 Arabic "
                    "transcription → branded .SubArabify.ar.srt next to the video.",
        epilog=(
            "examples:\n"
            "  python colab/client.py 'Movie.mkv' --url https://x.trycloudflare.com\n"
            "  python colab/client.py a.mp4 b.mkv --url $SUBARABIFY_COLAB_URL --force\n"
            "  python colab/client.py --url https://x.trycloudflare.com --job <id> \\\n"
            "       --save-to 'Movie.mkv'          # resume after a disconnect\n"
            "exit codes: 0 ok · 1 error · 2 timeout (job may still finish — resume)"))
    ap.add_argument("videos", nargs="*", help="local .mp4/.mkv/.avi/… files (batch ok)")
    ap.add_argument("--url", help="public tunnel URL from the Colab notebook "
                    "(also: SUBARABIFY_COLAB_URL env)")
    ap.add_argument("--job", help="resume an existing job id: poll + download only")
    ap.add_argument("--save-to", help="video path used for output naming when --job "
                    "is set with no positional videos")
    ap.add_argument("--sync", action="store_true",
                    help="single blocking /transcribe call — small clips only")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                    help="overall deadline in seconds per job (default 5400)")
    ap.add_argument("--poll", type=float, default=POLL,
                    help=f"seconds between status polls (default {POLL:g})")
    ap.add_argument("--force", action="store_true",
                    help="overwrite existing .SubArabify.ar.srt")
    ap.add_argument("--keep-wav", action="store_true",
                    help="leave the extracted 16 kHz WAV in a temp folder")
    ap.add_argument("--transport", choices=("auto", "wav", "opus"), default="auto",
                    help="auto = raw WAV ≤90 MB else Opus (default); wav/opus force")
    args = ap.parse_args(argv)

    if not args.videos and not args.job:
        ap.error("give at least one video, or --job <id> to resume")
    if args.sync and args.job:
        ap.error("--sync and --job are mutually exclusive")

    base = resolve_base(args)

    exit_code = 0
    try:
        _check_backend(base)
        if args.job:
            resume_job(args, base)
        else:
            for v in args.videos:
                try:
                    process_video(v, args, base)
                except (ClientError, JobTimeout) as e:
                    print(f"[fail] {v}: {e}", file=sys.stderr)
                    exit_code = 2 if isinstance(e, JobTimeout) else exit_code or 1
    except (JobTimeout,) as e:
        print(f"[fail] {e}", file=sys.stderr)
        return 2
    except (ClientError, requests.RequestException, OSError) as e:
        print(f"[fail] {e}", file=sys.stderr)
        return 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())