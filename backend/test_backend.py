"""Backend wiring smoke-test (no GPU, torch/transformers stubbed).

Runs locally by CI. Directs:
  * parse_srt / post_process_arabic / translate_srt on a fake MT
  * the FastAPI app through TestClient: health, error-path job lifecycle,
    srt-translate job error propagation, sync translate error.
Purely structural: real inference needs the T4.
"""
import json
import sys
import types
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")

# --- stub torch so cell-03 can import without CUDA -----------------------
_stub_torch = types.ModuleType("torch")
_stub_torch.cuda = types.ModuleType("torch.cuda")
_stub_torch.cuda.is_available = lambda: False
_stub_torch.cuda.get_device_name = lambda *a: "STUB"
_stub_torch.float16 = "float16"
sys.modules["torch"] = _stub_torch

# --- import cell-03 -------------------------------------------------------
NB = json.loads((Path(__file__).resolve().parent / "SubArabify_Backend.ipynb")
                .read_text(encoding="utf-8"))
CELL3 = "".join(next(c["source"] for c in NB["cells"] if c["id"] == "cell-03"))
NS = {}
exec(compile(CELL3, "<cell-03>", "exec"), NS)

SRT = ("1\n00:00:01,000 --> 00:00:04,500\nHello world.\n\n"
       "2\n00:00:05,000 --> 00:00:08,000\nHow are you?\n")
PASSES, FAILURES = [], []


def check(name, cond, detail=""):
    (PASSES if cond else FAILURES).append(name)
    print(f"{'PASS' if cond else 'FAIL'} {name}"
          + ("" if cond else f"  {detail}"))


# ── pure bits ─────────────────────────────────────────────────────────────
blocks = NS["parse_srt"](SRT)
check("parse_srt structure", len(blocks) == 2)
check("parse_srt timings ms", blocks[0]["start_ms"] == 1000
      and blocks[0]["end_ms"] == 4500, blocks[0])
check("parse_srt lines", blocks[0]["lines"] == ["Hello world."],
      str(blocks[0]["lines"]))

AR = NS["post_process_arabic"]
check("post-process spacing", AR("مرحبا ، كيف حالك  ؟") == "مرحبا، كيف حالك؟",
      repr(AR("مرحبا ، كيف حالك  ؟")))


def fake_mt(text, **kw):
    return [{"translation_text": "نص مترجم للاختبار ، مع سؤال ؟"}]


ORIG_GET_MT = NS["get_mt"]           # restore below: transformers is absent
NS["get_mt"] = lambda: fake_mt
try:
    out_srt = NS["translate_srt"](SRT, lambda p, n: None)
    failed = ""
except Exception as e:
    out_srt, failed = "", f"{type(e).__name__}: {e}"
check("translate_srt runs on fake MT", not failed, failed)
if not failed:
    tblocks = NS["parse_srt"](out_srt)
    check("translate keeps source timings", tblocks[0]["start_ms"] == 1000
          and tblocks[0]["end_ms"] == 4500, str(tblocks[0]))
    check("translate text is arabic", tblocks[0]["lines"][0]
          == "نص مترجم للاختبار، مع سؤال؟", str(tblocks[0]["lines"]))
    check("translate has both cues", len(tblocks) == 2, str(tblocks))

NS["get_mt"] = ORIG_GET_MT          # transformérs absent -> ImportError in jobs

# ── server wiring through TestClient ──────────────────────────────────────
from fastapi.testclient import TestClient  # noqa

client = TestClient(NS["app"])

r = client.get("/health")
check("/health fields", r.json()["version"] == "0.2.2-beta"
      and r.json()["mt_loaded"] is False and r.json()["status"] == "ok", r.text)

r = client.get("/")
check("/ endpoints list", "file=audio|srt" in r.text, r.text)

# translate job via srt= -> transformers missing -> job error, surfaced
r = client.post("/jobs", files={"srt": ("x.srt", SRT.encode(),
                                        "application/x-subrip")})
check("/jobs srt -> 202 + kind translate", r.status_code == 202
      and r.json().get("kind") == "translate", (r.status_code, r.text))
jid = r.json()["job_id"]
st = client.get(f"/jobs/{jid}").json()
check("translate job errors w/o transformers",
      st["status"] == "error"
      and ("ImportError" in st["message"] or "transformers" in st["message"]),
      st)
r = client.get(f"/jobs/{jid}/srt")
check("srt 409 on error", r.status_code == 409)

# audio job -> ffmpeg/import missing -> clean error path
r = client.post("/jobs", files={"file": ("m.wav", b"RIFF----WAVE",
                                         "audio/wav")})
check("/jobs file -> 202 + kind transcribe", r.status_code == 202
      and r.json().get("kind") == "transcribe", (r.status_code, r.text))
sing = client.post("/transcribe", files={"file": ("m.wav", b"RIFF", "audio/wav")})
check("/transcribe audio error surfaced", sing.status_code == 500
      and ("no decodable audio" in sing.text or "ffmpeg" in sing.text.lower()),
      sing.text)

r = client.post("/jobs")  # neither field
check("/jobs rejects empty", r.status_code == 400, (r.status_code, r.text))
r = client.post("/jobs/url", json={"url": "https://x.example/a.srt"})
check("/jobs/url rejects srt", r.status_code == 400, (r.status_code, r.text))

print()
if FAILURES:
    print(f"{len(FAILURES)} backend smoke-check failure(s): {FAILURES}")
    sys.exit(1)
print(f"all {len(PASSES)} backend smoke checks passed")