"""Self-tests for colab/client.py.

Runs:  python colab/test_client.py        (also wired into CI)

Covers:
  * pure units — transport decision, output naming, SRT branding via srtcore
  * a mock backend (http.server) — submit → poll → fetch → branded file
  * a full end-to-end CLI run when ffmpeg is installed (generates a tiny mp4)
  * timeout behaviour — a stuck job trips exit code 2 and prints the --job resume cmd
"""
import http.server
import importlib.util
import json
import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

COLAB_DIR = Path(__file__).resolve().parent
CLIENT = COLAB_DIR / "client.py"

SPEC = importlib.util.spec_from_file_location("sbc_client", CLIENT)
C = importlib.util.module_from_spec(SPEC)
sys.modules["sbc_client"] = C
SPEC.loader.exec_module(C)  # noqa

SAMPLE_SRT = """1
00:00:05,000 --> 00:00:07,000
مرحبا ، كيف حالك ؟

2
00:01:30,000 --> 00:01:32,000
لنذهب الآن.
"""


class _Backend(http.server.BaseHTTPRequestHandler):
    """Mock backend. STUCK=True ⇒ the job never finishes (timeout test)."""
    STUCK = False
    polls = 0
    handler_holder = []  # keep a ref alive for the whole server lifetime

    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, body, code=200):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            return self._json({"status": "ok",
                               "model": "samil24/whisper-large-arabic-dialects-v5",
                               "version": "1.0.4-pre", "model_loaded": True,
                               "device": "NVIDIA T4"})
        if self.path == "/jobs/j123/srt":
            return self._text(SAMPLE_SRT)
        if self.path.startswith("/jobs/"):
            type(self).polls += 1
            if type(self).STUCK or type(self).polls < 2:
                return self._json({"status": "processing", "progress": 0.5,
                                   "message": "window 3 · 900s / 1800s",
                                   "job_id": "j123"})
            return self._json({"status": "done", "progress": 1.0,
                               "message": "412 cues", "job_id": "j123"})
        return self._json({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        return self._json({"job_id": "j123", "status": "queued"}, 202)


def start_backend(stuck=False):
    _Backend.STUCK = stuck
    _Backend.polls = 0
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), _Backend)
    httpd.daemon_threads = True
    _Backend.handler_holder.append(httpd)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}"


def _tiny_mp4(path: Path) -> None:
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error",
         "-f", "lavfi", "-i", "testsrc=duration=2:size=128x128:rate=10",
         "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
         "-shortest", "-c:v", "libx264", "-preset", "ultrafast",
         "-c:a", "aac", str(path)],
        check=True, capture_output=True)


def ffmpeg_available() -> bool:
    return bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


# ─────────────────────────── pure units ───────────────────────────

def test_transport_decision():
    assert C.decide_transport(10 * 1024 * 1024, "auto") == "wav"
    assert C.decide_transport(300 * 1024 * 1024, "auto") == "opus"
    assert C.decide_transport(300 * 1024 * 1024, "wav") == "wav"
    assert C.decide_transport(10 * 1024 * 1024, "opus") == "opus"


def test_output_naming_and_branding():
    with tempfile.TemporaryDirectory() as d:
        video = Path(d) / "My.Movie.2023.1080p.WEB-DL.mkv"
        out = C.brand_and_write(SAMPLE_SRT, video, force=True)
        # smart_base strips ext + lang/brand tags only (parity with the app):
        # resolution tags stay, exactly like the addon writes them
        assert out.name == "My.Movie.2023.1080p.WEB-DL.SubArabify.ar.srt", out.name
        text = out.read_text(encoding="utf-8")
        assert "— SubArabify —" in text and "NOTE" in text
        assert "00:00:05,000 --> 00:00:07,000" in text      # dialogue kept
        assert "مرحبا، كيف حالك؟" in text                      # Arabic post-fix
        assert "00:01:30,000 --> 00:01:32,000" in text


def test_partial_overwrite_refused():
    with tempfile.TemporaryDirectory() as d:
        video = Path(d) / "A.mkv"
        out = C.brand_and_write(SAMPLE_SRT, video, force=True)
        out.write_text("X", encoding="utf-8")
        try:
            C.brand_and_write(SAMPLE_SRT, video, force=False)
            raise AssertionError("expected ClientError for existing output")
        except C.ClientError:
            assert out.read_text(encoding="utf-8") == "X"


# ─────────────────────────── mock-backend e2e ───────────────────────────

def test_cli_e2e_with_mock_backend():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            video = tmp / "Sample.Movie.mkv"
            if ffmpeg_available():
                _tiny_mp4(video)
            else:
                video.write_bytes(b"not really a video")
            r = subprocess.run(
                [sys.executable, str(CLIENT), str(video), "--url", base,
                 "--timeout", "60", "--poll", "0.05"],
                capture_output=True, text=True, timeout=180)
            if ffmpeg_available():
                assert r.returncode == 0, (r.stdout, r.stderr)
                out = video.parent / "Sample.Movie.SubArabify.ar.srt"
                assert out.exists(), r.stdout
                assert "مرحبا" in out.read_text(encoding="utf-8")
                # second run skips without --force
                r2 = subprocess.run(
                    [sys.executable, str(CLIENT), str(video), "--url", base],
                    capture_output=True, text=True, timeout=60)
                assert r2.returncode == 0 and "[skip]" in r2.stdout, r2.stdout
            else:
                assert r.returncode == 1 and "ffmpeg" in r.stderr.lower(), r.stderr
    finally:
        httpd.shutdown()


def test_cli_resume_job():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            r = subprocess.run(
                [sys.executable, str(CLIENT), "--url", base, "--job", "j123",
                 "--save-to", str(tmp / "Movie.mkv"), "--poll", "0.05",
                 "--timeout", "60"],
                capture_output=True, text=True, timeout=120)
            assert r.returncode == 0, (r.stdout, r.stderr)
            out = tmp / "Movie.SubArabify.ar.srt"
            assert out.exists() and "NOTE" in out.read_text(encoding="utf-8")
    finally:
        httpd.shutdown()


def test_cli_timeout_prints_resume_cmd():
    httpd, base = start_backend(stuck=True)
    try:
        r = subprocess.run(
            [sys.executable, str(CLIENT), "--url", base, "--job", "j123",
             "--save-to", str(Path("V") / "Movie.mkv"), "--poll", "0.05",
             "--timeout", "1"],
            capture_output=True, text=True, timeout=90)
        assert r.returncode == 2, (r.returncode, r.stdout, r.stderr)
        assert "--job" in r.stderr and "resume" in r.stderr.lower(), r.stderr
    finally:
        httpd.shutdown()


def test_missing_url_error():
    if "SUBARABIFY_COLAB_URL" in os.environ:
        os.environ.pop("SUBARABIFY_COLAB_URL")
    r = subprocess.run([sys.executable, str(CLIENT), "a.mp4"],
                       capture_output=True, text=True, timeout=60)
    assert r.returncode == 1
    assert "SUBARABIFY_COLAB_URL" in r.stderr or "--url" in r.stderr


if __name__ == "__main__":
    funcs = {k: v for k, v in globals().items()
             if k.startswith("test_") and callable(v)}
    fails = 0
    for name, fn in sorted(funcs.items()):
        try:
            fn()
            print(f"PASS {name}")
        except Exception as e:  # noqa: BLE001
            fails += 1
            import traceback
            print(f"FAIL {name}")
            traceback.print_exc()
    if fails:
        print(f"{fails} colab client test(s) failed")
        sys.exit(1)
    print("all colab client tests passed")