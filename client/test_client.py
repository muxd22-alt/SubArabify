"""Self-tests for client/client.py — SubArabify v0.2.2-beta.

Runs:  python client/test_client.py        (also wired into CI)

Covers:
  * pure units — transport decision, output naming, SRT branding via srtcore
  * routing — existing Arabic output → skip; English .srt → translate;
    no subtitle → transcribe the audio
  * a mock backend (http.server) — submit (audio + subtitle) → poll → fetch →
    branded file
  * a full end-to-end CLI run when ffmpeg is installed (tiny mp4 → transcribe)
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

CLIENT_DIR = Path(__file__).resolve().parent
CLIENT = CLIENT_DIR / "client.py"

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


def make_eng_srt(path: Path, cues: int = 40) -> Path:
    parts = []
    for i in range(cues):
        s, e = 10 + i * 8, 13 + i * 8
        parts.append(f"{i + 1}\n00:{s // 60:02d}:{s % 60:02d},000 --> "
                     f"00:{e // 60:02d}:{e % 60:02d},000\n"
                     f"This is line number {i + 1} of the subtitle.\n")
    path.write_text("\n".join(parts), encoding="utf-8")
    return path


class _Backend(http.server.BaseHTTPRequestHandler):
    """Mock backend. STUCK=True ⇒ the job never finishes (timeout test)."""
    STUCK = False
    polls = 0
    last_field = None
    handler_holder = []

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
                               "version": "0.2.2-beta", "model_loaded": True,
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
        body = self.rfile.read(length) if length else b""
        if b'name="srt"' in body:
            type(self).last_field = "srt"
        elif b'name="file"' in body:
            type(self).last_field = "file"
        return self._json({"job_id": "j123", "status": "queued"}, 202)


def start_backend(stuck=False):
    _Backend.STUCK = stuck
    _Backend.polls = 0
    _Backend.last_field = None
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


# ─────────────────────────── routing units ───────────────────────────

def test_routing_existing_ar_skip():
    with tempfile.TemporaryDirectory() as d:
        folder = Path(d)
        (folder / "Movie.mkv").write_bytes(b"x")
        (folder / "Movie.SubArabify.ar.srt").write_text("x", encoding="utf-8")
        assert C.existing_ar(folder, "Movie") is not None
        assert C.find_source(folder, "Movie") is None  # never our own output


def test_routing_finds_english_srt():
    with tempfile.TemporaryDirectory() as d:
        folder = Path(d)
        (folder / "Movie.2023.mkv").write_bytes(b"x")
        en = folder / "Movie.2023.en.srt"
        en.write_text("x", encoding="utf-8")
        fr = folder / "Movie.2023.fr.srt"  # exact-key fuzzy fallback target
        fr.write_text("x" * 5000, encoding="utf-8")
        assert C.find_source(folder, "Movie.2023") == en

        bare = folder / "Movie.2023.srt"
        bare.write_text("x", encoding="utf-8")
        assert C.find_source(folder, "Movie.2023") == en  # tagged wins

        (folder / "Movie.2023.en.srt").unlink()
        assert C.find_source(folder, "Movie.2023") == bare

        (folder / "Movie.2023.srt").unlink()
        assert C.find_source(folder, "Movie.2023") == fr  # fuzzy any-language


# ─────────────────────────── mock-backend e2e ───────────────────────────

def test_cli_e2e_translate_no_ffmpeg_needed():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            video = tmp / "Sample.Movie.mkv"
            video.write_bytes(b"not really a video")
            make_eng_srt(tmp / "Sample.Movie.en.srt")
            r = subprocess.run(
                [sys.executable, str(CLIENT), str(video), "--url", base,
                 "--timeout", "60", "--poll", "0.05"],
                capture_output=True, text=True, timeout=180)
            assert r.returncode == 0, (r.stdout, r.stderr)
            assert _Backend.last_field == "srt", "english srt present → translate job"
            out = tmp / "Sample.Movie.SubArabify.ar.srt"
            assert out.exists(), r.stdout
            assert "NOTE" in out.read_text(encoding="utf-8")
            r2 = subprocess.run(
                [sys.executable, str(CLIENT), str(video), "--url", base],
                capture_output=True, text=True, timeout=60)
            assert r2.returncode == 0 and "[skip]" in r2.stdout, r2.stdout
    finally:
        httpd.shutdown()


def test_cli_e2e_with_mock_backend_transcribe():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            video = tmp / "Sample.Movie.mkv"
            if not ffmpeg_available():
                video.write_bytes(b"not really a video")
                r = subprocess.run(
                    [sys.executable, str(CLIENT), str(video), "--url", base,
                     "--timeout", "60", "--poll", "0.05"],
                    capture_output=True, text=True, timeout=180)
                # no subtitle + no ffmpeg → clean client-side error
                assert r.returncode == 1 and "ffmpeg" in r.stderr.lower(), r.stderr
            else:
                _tiny_mp4(video)
                r = subprocess.run(
                    [sys.executable, str(CLIENT), str(video), "--url", base,
                     "--timeout", "60", "--poll", "0.05"],
                    capture_output=True, text=True, timeout=180)
                assert r.returncode == 0, (r.stdout, r.stderr)
                assert _Backend.last_field == "file", "no subtitle → transcribe job"
                out = tmp / "Sample.Movie.SubArabify.ar.srt"
                assert out.exists(), r.stdout
                assert "NOTE" in out.read_text(encoding="utf-8")
    finally:
        httpd.shutdown()


def test_cli_media_folder_scan_routing():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            lib = Path(d) / "lib"
            lib.mkdir()
            m1 = lib / "Movie.One.mkv"
            m1.write_bytes(b"x")
            make_eng_srt(lib / "Movie.One.en.srt")
            m2 = lib / "Movie.Two.mkv"
            m2.write_bytes(b"x")
            (lib / "Movie.Two.SubArabify.ar.srt").write_text("x", encoding="utf-8")
            r = subprocess.run(
                [sys.executable, str(CLIENT), "--media", str(lib), "--url", base,
                 "--timeout", "60", "--poll", "0.05"],
                capture_output=True, text=True, timeout=180)
            assert r.returncode == 0, (r.stdout, r.stderr)
            assert r.stdout.count("[skip]") >= 1, ("Movie.Two should skip", r.stdout)
            assert (lib / "Movie.One.SubArabify.ar.srt").exists(), r.stdout
            assert "[scan]" in r.stdout
            assert _Backend.last_field == "srt"
    finally:
        httpd.shutdown()


def test_cli_weak_source_skipped():
    httpd, base = start_backend()
    try:
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            video = tmp / "Stub.mkv"
            video.write_bytes(b"x")
            make_eng_srt(tmp / "Stub.en.srt", cues=5)  # promo-stub sized
            r = subprocess.run(
                [sys.executable, str(CLIENT), str(video), "--url", base],
                capture_output=True, text=True, timeout=120)
            assert r.returncode == 0, (r.stdout, r.stderr)
            assert "weak_source" in r.stdout
            assert not (tmp / "Stub.SubArabify.ar.srt").exists()
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
        print(f"{fails} client test(s) failed")
        sys.exit(1)
    print("all client tests passed")