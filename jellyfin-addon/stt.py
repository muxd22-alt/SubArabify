"""Offline speech-to-text for the SubArabify Jellyfin addon (v1.0.3-beta).

Used only when a video has NO usable subtitle file (WhisperSubs-style
fallback): extract 16 kHz mono audio with ffmpeg (Jellyfin's bundled
ffmpeg is probed first), transcribe locally with faster-whisper
(`tiny.en` by default — English audio), group words into cues with real
timestamps. The caller then runs the normal EN→AR translation on them.

Env:
  SUBARABIFY_STT_MODEL  whisper model (default "tiny.en";
                        "base.en"/"small.en" are slower but more accurate)
  SUBARABIFY_CACHE      cache dir (models land in <cache>/models-stt)
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ADDON_DIR = Path(__file__).resolve().parent
CACHE_DIR = Path(os.environ.get("SUBARABIFY_CACHE", str(ADDON_DIR / ".cache")))
STT_MODEL = os.environ.get("SUBARABIFY_STT_MODEL", "tiny.en")

MAX_CUE_CHARS = 42
MAX_CUE_S = 6.0
GAP_SPLIT_S = 0.8


def find_ffmpeg():
    cands = [
        "/usr/lib/jellyfin-ffmpeg/ffmpeg",  # Jellyfin's bundled ffmpeg
        shutil.which("ffmpeg") or "",
        "/data/data/com.termux/files/usr/bin/ffmpeg",  # Termux (pkg install ffmpeg)
    ]
    for c in cands:
        if c and Path(c).exists():
            return c
    return None


def group_words(words):
    """Pure function: [{start,end,word}] -> cue dicts. Tested in test_srtcore.py style."""
    cues, cur = [], []
    cur_len = 0

    def flush():
        nonlocal cur, cur_len
        if not cur:
            return
        start = cur[0]["start"]
        end = max(cur[-1]["end"], start + 0.8)
        cues.append({"start": start, "end": end,
                     "text": " ".join(w["word"] for w in cur)})
        cur, cur_len = [], 0

    for i, w in enumerate(words):
        if not w.get("word", "").strip():
            continue
        if cur:
            gap = w["start"] - cur[-1]["end"]
            dur = w["end"] - cur[0]["start"]
            if gap >= GAP_SPLIT_S or dur >= MAX_CUE_S \
                    or cur_len + len(w["word"]) + 1 > MAX_CUE_CHARS:
                flush()
        cur.append({"start": w["start"], "end": w["end"], "word": w["word"].strip()})
        cur_len += len(w["word"]) + 1
        if i == len(words) - 1:
            flush()
    return cues


def transcribe(video: Path, model_name: str = STT_MODEL, on_progress=None):
    """Returns (cues, heard_anything). Raises RuntimeError with a clear cause."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        raise RuntimeError("faster-whisper not installed (pip install -r requirements.txt)")

    ffmpeg = find_ffmpeg()
    if ffmpeg is None:
        raise RuntimeError("ffmpeg not found (Jellyfin bundles one at "
                           "/usr/lib/jellyfin-ffmpeg/ffmpeg; Termux: pkg install ffmpeg)")

    print(f"[stt] loading whisper model '{model_name}' (first use downloads once)…",
          file=sys.stderr)
    model = WhisperModel(model_name, device="cpu", compute_type="int8",
                         download_root=str(CACHE_DIR / "models-stt"))

    tmp = Path(tempfile.mkdtemp(prefix="subarabify-stt-"))
    wav = tmp / "audio.wav"
    try:
        r = subprocess.run(
            [ffmpeg, "-nostdin", "-y", "-v", "error",
             "-i", str(video), "-map", "0:a:0",
             "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
            capture_output=True, text=True, timeout=3600,
        )
        if r.returncode != 0 or not wav.exists():
            raise RuntimeError(f"audio extraction failed: {r.stderr.strip()[:300]}")
        segments, _info = model.transcribe(str(wav), language="en", beam_size=1,
                                           vad_filter=True,
                                           vad_parameters={"min_silence_duration_ms": 500})
        words = []
        for seg in segments:
            if getattr(seg, "words", None):
                for w in seg.words:
                    words.append({"start": w.start, "end": w.end, "word": w.word})
            elif seg.text.strip():
                # no word timestamps: split evenly as a last resort
                toks = seg.text.split()
                span = max(seg.end - seg.start, 0.1)
                for k, t in enumerate(toks):
                    s = seg.start + span * k / len(toks)
                    words.append({"start": s, "end": s + span / len(toks), "word": t})
            if on_progress is not None:
                on_progress(seg.end)
        cues = group_words(words)
        return cues, len(words) > 0
    finally:
        try:
            shutil.rmtree(tmp, ignore_errors=True)
        except Exception:
            pass
