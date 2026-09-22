"""Self-tests for jellyfin-addon/subarabify_jellyfin.py — v0.2.3-alpha.

Runs:   python jellyfin-addon/test_addon.py      (also wired into CI)

Covers:
  * unified config precedence  --url > $SUBARABIFY_COLAB_URL > config.json
  * QR on TV  --qrcode (ascii/png; skipped if the optional lib is absent) +
    the always-available banner fallback
  * LAN config endpoint --serve (GET /subarabify/config -> colab_url)
  * routing: existing Arabic -> skip · English .srt -> translate ·
    none -> transcribe (direct file upload, NO ffmpeg for files <=90 MB)
  * a mock backend round trip (submit -> poll -> fetch -> branded SRT)
  * the Jellyfin manifest template stays valid + complete
"""
import argparse
import http.server
import importlib.util
import io
import json
import os
import socketserver
import sys
import tempfile
import threading
import uuid
from contextlib import redirect_stdout
from pathlib import Path

ADDON_DIR = Path(__file__).resolve().parent
ADDON = ADDON_DIR / "subarabify_jellyfin.py"

SPEC = importlib.util.spec_from_file_location("sbc_addon", ADDON)
A = importlib.util.module_from_spec(SPEC)
sys.modules["sbc_addon"] = A
SPEC.loader.exec_module(A)  # noqa

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


def make_args(**kw):
    d = {"media": [], "force": False, "poll": 0.05, "timeout": 60,
         "interval": None, "url": None, "save_to": None, "job": None,
         "qr_png": None, "port": None, "watch": False, "serve": False,
         "qrcode": False}
    d.update(kw)
    return argparse.Namespace(**d)


class _Backend(http.server.BaseHTTPRequestHandler):
    polls = 0
    last_field = None

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
            return self._json({"status": "ok", "version": "0.2.3-alpha",
                               "model": "m", "model_loaded": True, "mt_loaded": True})
        if self.path == "/jobs/j1/srt":
            return self._text(SAMPLE_SRT)
        if self.path.startswith("/jobs/"):
            type(self).polls += 1
            if type(self).polls < 2:
                return self._json({"status": "processing", "progress": 0.5,
                                   "message": "window 3", "job_id": "j1"})
            return self._json({"status": "done", "progress": 1.0,
                               "message": "ok", "job_id": "j1"})
        return self._json({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        type(self).last_field = "srt" if b'name="srt"' in body else \
            ("file" if b'name="file"' in body else None)
        return self._json({"job_id": "j1", "status": "queued"}, 202)


def start_backend():
    _Backend.polls = 0
    _Backend.last_field = None
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), _Backend)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{httpd.server_address[1]}"


_failures = 0


def check(name, cond, detail=""):
    global _failures
    mark = "ok " if cond else "FAIL"
    if not cond:
        _failures += 1
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail and cond else f" — {detail}" if detail else ""))


def test_version():
    print("· version")
    check("VERSION == 0.2.3-alpha", A.VERSION == "0.2.3-alpha", A.VERSION)
    check("srtcore copy == 0.2.3-alpha", getattr(A.srtcore, "APP_VERSION", "") == "0.2.3-alpha")


def test_config_precedence():
    print("· config precedence (--url > env > config.json)")
    with tempfile.TemporaryDirectory() as td:
        cfg = {"colab_url": "https://file-url.example"}
        p = Path(td) / "config.json"
        p.write_text(json.dumps(cfg), encoding="utf-8")
        loaded = A.load_config(p)
        check("reads colab_url from file", A.resolve_base(None, loaded) == "https://file-url.example")
        flag = A.resolve_base("https://flag.example", loaded)
        check("--url flag wins over file", flag == "https://flag.example", flag)
        old = os.environ.get("SUBARABIFY_COLAB_URL")
        os.environ["SUBARABIFY_COLAB_URL"] = "https://env.example"
        try:
            check("env wins over file", A.resolve_base(None, loaded) == "https://env.example")
            check("flag wins over env", A.resolve_base("https://flag.example", loaded) == "https://flag.example")
        finally:
            if old is None:
                os.environ.pop("SUBARABIFY_COLAB_URL", None)
            else:
                os.environ["SUBARABIFY_COLAB_URL"] = old
        try:
            A.resolve_base(None, {})
            check("missing URL raises AddonError", False)
        except A.AddonError as e:
            check("missing URL raises AddonError", "no backend URL" in str(e))
        check("trailing slash stripped", A.resolve_base("https://x.example/", {}) == "https://x.example")


def test_qrcode():
    print("· QR on TV")
    if A.qrcode is None:
        print("  [skip] qrcode lib not installed (CI installs it)")
        return
    q = A.render_qr("https://snake.trycloudflare.com")
    check("ascii QR rendered", q is not None and len(q) > 50)
    with tempfile.TemporaryDirectory() as td:
        png = Path(td) / "q.png"
        out = A.qr_banner("https://snake.trycloudflare.com", str(png))
        check("banner includes url", "https://snake.trycloudflare.com" in out)
        check("QR png written", png.exists() and png.stat().st_size > 100)


def test_banner_fallback():
    print("· banner fallback (always available)")
    out = A.qr_banner("https://u.example", None)
    check("url visible", "https://u.example" in out)
    check("app hint visible", "Backend card" in out)


def test_config_server():
    print("· LAN config endpoint (--serve)")
    httpd, url = A.start_config_server("https://base.example", {"poll": 7}, 0)
    import urllib.request
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{httpd.server_address[1]}/subarabify/config", timeout=5) as r:
            j = json.loads(r.read().decode("utf-8"))
        check("GET /subarabify/config returns colab_url", j.get("colab_url") == "https://base.example", str(j))
        check("service name present", j.get("service") == A.SERVICE_NAME)
        with urllib.request.urlopen(f"http://127.0.0.1:{httpd.server_address[1]}/", timeout=5) as r:
            j2 = json.loads(r.read().decode("utf-8"))
        check("GET / serves same payload", j2.get("colab_url") == "https://base.example")
    finally:
        httpd.shutdown()


def test_routing():
    print("· routing decisions")
    url = start_backend()
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        base = A.resolve_base(url, {})
        video = td / "The.Movie.2024.1080p.WEB-DL.mkv"
        video.touch()

        out = td / f"{video.stem}.SubArabify.ar.srt"
        out.touch()
        st = A.handle_video(video, make_args(), base)
        check("existing Arabic → skip", st == "done", st)
        out.unlink()

        eng = make_eng_srt(td / "The.Movie.en.srt")
        st = A.handle_video(video, make_args(), base)
        check("English .srt → translate_success", st == "translate_success", st)
        written = td / f"{video.stem}.SubArabify.ar.srt"
        check("branded file written", written.exists(), str(written))
        eng.unlink()
        written.unlink()

        stub = td / "The.Movie.srt"
        stub.write_text(SAMPLE_SRT, encoding="utf-8")  # 2 cues < MIN_CUES
        st = A.handle_video(video, make_args(), base)
        check("promo stub → weak_source", st == "weak_source", st)
        stub.unlink()

        st = A.handle_video(video, make_args(), base)
        check("no subtitle → transcribe_success (direct upload, no ffmpeg)",
              st == "transcribe_success", st)
        written = td / f"{video.stem}.SubArabify.ar.srt"
        check("branded file written for transcription", written.exists(), str(written))


def test_payload_decision():
    print("· transcribe payload decision (no ffmpeg needed ≤90 MB)")
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        small = td / "s.webm"
        small.write_bytes(b"not really a video but small")
        tmp = Path(tempfile.mkdtemp(prefix="addon-test-"))
        try:
            payload, name, mime = A.pick_transcribe_payload(small, tmp)
            check("small file sent directly", payload == small and name == "s.webm")
            check("video mime selected", mime == "video/webm", mime)
        finally:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)


def test_manifest_template():
    print("· Jellyfin manifest template")
    m = json.loads((ADDON_DIR / "manifest.json").read_text(encoding="utf-8"))
    for k in ("guid", "name", "version", "targetAbi", "artifactTypes", "artifacts"):
        check(f"manifest.{k} present", k in m)
    try:
        uuid.UUID(m["guid"])
        check("guid is a real uuid", True)
    except (ValueError, TypeError):
        check("guid is a real uuid", False, m.get("guid"))
    check("artifact has filename+checksum",
          "filename" in m["artifacts"][0] and "checksum" in m["artifacts"][0])


def main():
    global _failures
    print("jellyfin-addon/test_addon.py")
    test_version()
    test_config_precedence()
    test_qrcode()
    test_banner_fallback()
    test_config_server()
    test_routing()
    test_payload_decision()
    test_manifest_template()
    print(f"\n{0 if _failures == 0 else _failures} failure(s)")
    return 1 if _failures else 0


if __name__ == "__main__":
    sys.exit(main())