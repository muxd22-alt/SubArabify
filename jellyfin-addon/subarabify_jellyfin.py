#!/usr/bin/env python3
"""SubArabify Jellyfin addon — on-server EN→AR subtitles (v1.0.4-pre).

Runs natively on a normal media server AND on Android via Termux
(no .NET needed — pure Python, so Termux works out of the box).

What it does when enabled:
  1. FIRST downloads the small Arabic NMT model once
     (default: Helsinki-NLP/opus-mt-en-ar, ~200 MB, CPU-friendly).
  2. Scans your media folders for movies/episodes.
  3. Finds the English subtitle (exact → fuzzy → any-language → .vtt).
  4. Translates it OFFLINE in context chunks with a persistent
     translation memory (no lag on re-runs).
  5. Writes `Movie.SubArabify.ar.srt` next to the video with the
     SubArabify brand — dialogue timings copied bit-identical,
     brand cues only in free gaps (never hurts sync).
  6. Optionally tells Jellyfin to refresh the library.

Usage:
  Termux (Android):
    pkg install python git
    pip install -r requirements.txt
    python subarabify_jellyfin.py --media ~/storage/movies --once
    # or continuous:  python subarabify_jellyfin.py --media ~/storage/movies --watch

  Normal server:
    pip install -r requirements.txt
    python subarabify_jellyfin.py --media /media/movies --media /media/shows --watch
    # with Jellyfin refresh:
    JELLYFIN_URL=http://localhost:8096 JELLYFIN_API_KEY=xxx \\
      python subarabify_jellyfin.py --media /media --watch --jellyfin-refresh
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

import srtcore

ADDON_DIR = Path(__file__).resolve().parent
MODEL_ID = os.environ.get("SUBARABIFY_MODEL", "Helsinki-NLP/opus-mt-en-ar")
CACHE_DIR = Path(os.environ.get("SUBARABIFY_CACHE", str(ADDON_DIR / ".cache")))
TM_FILE = CACHE_DIR / "tm.json"
MIN_CUES = 30
MIN_BYTES = 2000

# Tiny instant glossary — same answers as the Android app, zero inference.
GLOSSARY = {
    "previously on": "في الحلقة السابقة",
    "to be continued": "يتبع",
    "the end": "النهاية",
    "yeah": "أجل", "yeah.": "أجل.", "yep": "أجل", "nope": "لا",
    "okay": "حسنًا", "okay.": "حسنًا.", "ok": "حسنًا", "ok.": "حسنًا.",
    "hey": "مهلًا", "hey!": "مهلًا!", "hi": "مرحبًا", "hello": "مرحبًا",
    "hello.": "مرحبًا.", "good morning": "صباح الخير", "good night": "تصبح على خير",
    "thank you": "شكرًا لك", "thanks": "شكرًا", "sorry": "آسف",
    "i'm sorry": "أنا آسف", "come on": "هيا", "come on!": "هيا!",
    "let's go": "هيا بنا", "watch out": "احذر", "watch out!": "احذر!",
    "help!": "النجدة!", "wow": "واو", "oh my god": "يا إلهي",
    "oh my god!": "يا إلهي!", "what?": "ماذا؟", "why?": "لماذا؟",
    "how?": "كيف؟", "where are you?": "أين أنت؟",
    "are you okay?": "هل أنت بخير؟", "i love you": "أحبك",
    "i don't know": "لا أعرف", "no way": "مستحيل", "no way!": "مستحيل!",
    "of course": "بالطبع", "exactly": "بالضبط",
}


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", srtcore.strip_markup(s)).strip().lower()


class TM:
    """Persistent translation memory (JSON). The 'no lag between runs' part."""

    def __init__(self, path: Path):
        self.path = path
        try:
            self.d = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        except Exception:
            self.d = {}

    def get(self, k):
        return self.d.get(k)

    def put(self, k, v):
        if k and v:
            self.d[k] = v

    def save(self):
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.d, ensure_ascii=False), encoding="utf-8")
        except Exception as e:
            print(f"[tm] save failed: {e}", file=sys.stderr)


_translator = None  # lazy (heavy imports only when needed)


def ensure_model():
    """Step 1 when the addon is enabled: download the small Arabic model once."""
    global _translator
    if _translator is not None:
        return _translator
    model_dir = CACHE_DIR / "models" / MODEL_ID.replace("/", "__")
    print(f"[model] small Arabic model: {MODEL_ID}")
    print(f"[model] cache: {model_dir}")
    try:
        from transformers import pipeline

        _translator = pipeline(
            "translation_en_to_ar",
            model=MODEL_ID,
            device=-1,  # CPU — works on servers and phones
            model_kwargs={"cache_dir": str(model_dir)},
        )
        # warm up (forces the one-time download now, not mid-movie)
        _translator("Hello.", max_length=64)
        print("[model] ready (transformers, offline from now on).")
    except Exception as e:
        print(f"[model] transformers unavailable ({e}).")
        print("[model] falling back to glossary + echo (install requirements.txt for full NMT).")
        _translator = None
    return _translator


def translate_texts(texts, tm: TM):
    """Translate cores with glossary → memory → model chunks. Returns AR list."""
    out, pending_idx, pending = [], [], []
    for t in texts:
        n = norm(t)
        if not n or n.isdigit():
            out.append(t)
            continue
        if n in GLOSSARY:
            out.append(GLOSSARY[n])
            continue
        hit = tm.get(n)
        if hit:
            out.append(hit)
            continue
        out.append(None)
        pending_idx.append(len(out) - 1)
        pending.append(t)
    if pending:
        mt = ensure_model()
        # context chunks of ~600 chars → ~10 calls per movie, not ~1000
        chunks, cur, ln = [], [], 0
        for p in pending:
            if cur and ln + len(p) > 600:
                chunks.append(cur)
                cur, ln = [], 0
            cur.append(p)
            ln += len(p) + 4
        if cur:
            chunks.append(cur)
        for ch in chunks:
            arrow = "\n‖\n"
            if mt is None:
                ars = ch  # echo fallback — timings/file still correct
            elif len(ch) == 1:
                try:
                    ars = [mt(ch[0], max_length=256)[0]["translation_text"]]
                except Exception:
                    ars = ch
            else:
                try:
                    joined = mt(arrow.join(ch), max_length=1024)[0]["translation_text"]
                    parts = re.split(r"\s*‖\s*", joined)
                    ars = parts if len(parts) == len(ch) else [
                        mt(c, max_length=256)[0]["translation_text"] for c in ch
                    ]
                except Exception:
                    try:
                        ars = [mt(c, max_length=256)[0]["translation_text"] for c in ch]
                    except Exception:
                        ars = ch
            for src, ar in zip(ch, ars):
                ar = srtcore.post_process_arabic(ar)
                for i in pending_idx:
                    if out[i] is None and norm(pending[pending_idx.index(i)]) == norm(src):
                        out[i] = ar
                        tm.put(norm(src), ar)
                        break
        for i in pending_idx:
            if out[i] is None:
                out[i] = pending[pending_idx.index(i)]
    return out


def find_source(folder: Path, video_base: str):
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
            tag = 2 if (".en." in l or ".eng." in l or ".english." in l) else (1 if l.endswith(".srt") else 0)
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


def existing_ar(folder: Path, base: str):
    for cand in (f"{base}.SubArabify.ar.srt", f"{base}_SubArabify_ar.srt", f"{base}.ar.srt"):
        p = folder / cand
        if p.exists():
            return p
    for p in folder.iterdir():
        if p.is_file() and p.name.startswith(base) and "subarabify" in p.name.lower() \
                and p.suffix.lower() == ".srt":
            return p
    return None


def process_movie(video: Path, tm: TM, force=False, stt=False):
    folder, base = video.parent, video.stem
    if existing_ar(folder, base) and not force:
        return "done"
    src = find_source(folder, base)
    if src is None:
        # ── No subtitle file → optional speech-to-text from audio ──
        if stt:
            return transcribe_audio(video, tm, force=force)
        return "pending"
    try:
        raw = src.read_text(encoding="utf-8-sig")
    except Exception:
        try:
            raw = src.read_text(encoding="cp1256")
        except Exception as e:
            print(f"[{base}] read failed: {e}")
            return "error"
    blocks = srtcore.parse(raw)
    if not blocks:
        return "pending"
    if len(blocks) < MIN_CUES or not (src.stat().st_size >= MIN_BYTES):
        print(f"[{base}] weak source ({len(blocks)} cues) — add a full .en.srt")
        return "weak_source"
    # shield prefixes, translate cores
    jobs = []
    for b in blocks:
        for ln in b["lines"]:
            clean = srtcore.strip_markup(ln)
            prefix, core = srtcore.split_prefix(clean) if clean else ("", "")
            jobs.append((prefix, core))
    cores = [c for _, c in jobs]
    ars = translate_texts(cores, tm)
    translated = []
    k = 0
    for b in blocks:
        lines = []
        for _ in b["lines"]:
            prefix, _ = jobs[k]
            ar = ars[k] if k < len(ars) else ""
            lines.append(f"{prefix}{ar}".strip())
            k += 1
        translated.append(lines)
    content = srtcore.build_branded_srt(blocks, translated)
    out = folder / f"{base}.SubArabify.ar.srt"
    out.write_text(content, encoding="utf-8")
    print(f"[{base}] wrote {out.name} ({len(blocks)} cues, timings unchanged)")
    return "success"


def transcribe_audio(video: Path, tm: TM, force=False):
    """STT fallback (WhisperSubs-style): transcribe English audio offline,
    translate to Arabic, write the same branded file. Returns a status str."""
    import srtcore as _core  # local import: keeps module import light
    folder, base = video.parent, video.stem
    if existing_ar(folder, base) and not force:
        return "done"
    try:
        import stt as _stt
    except ImportError as e:
        print(f"[{base}] STT unavailable: {e}", file=sys.stderr)
        return "pending"
    try:
        cues, heard = _stt.transcribe(video)
    except RuntimeError as e:
        print(f"[{base}] STT skipped: {e}", file=sys.stderr)
        return "pending"
    except Exception as e:
        print(f"[{base}] STT failed: {e}", file=sys.stderr)
        return "error"
    if not heard or len(cues) < 10:
        print(f"[{base}] STT heard too little ({len(cues)} cues)")
        return "pending"
    blocks = [{"index": str(i + 1),
               "timecode": f"{_core.ms_to_tc(int(c['start'] * 1000))} --> "
                           f"{_core.ms_to_tc(int(c['end'] * 1000))}",
               "lines": [c["text"]]} for i, c in enumerate(cues)]
    cores = [c["text"] for c in cues]
    ars = translate_texts(cores, tm)
    translated = [[a] for a in ars]
    content = _core.build_branded_srt(blocks, translated)
    out = folder / f"{base}.SubArabify.ar.srt"
    out.write_text(content, encoding="utf-8")
    print(f"[{base}] wrote {out.name} from STT ({len(blocks)} cues, real audio timings)")
    return "stt_success"


def iter_videos(media_dirs):
    for d in media_dirs:
        root = Path(d).expanduser()
        if not root.exists():
            print(f"[scan] missing dir: {root}", file=sys.stderr)
            continue
        for p in root.rglob("*"):
            if p.is_file() and p.suffix.lower().lstrip(".") in srtcore.VIDEO_EXTS:
                yield p


def jellyfin_refresh():
    url = os.environ.get("JELLYFIN_URL", "").rstrip("/")
    key = os.environ.get("JELLYFIN_API_KEY", "")
    if not url or not key:
        return
    try:
        import urllib.request
        req = urllib.request.Request(
            f"{url}/Library/Refresh?api_key={key}", method="POST")
        urllib.request.urlopen(req, timeout=15).read()
        print("[jellyfin] library refresh triggered.")
    except Exception as e:
        print(f"[jellyfin] refresh failed: {e}", file=sys.stderr)


def scan(media_dirs, force=False, stt=False):
    tm = TM(TM_FILE)
    stats = {"success": 0, "stt_success": 0, "done": 0, "pending": 0,
             "weak_source": 0, "error": 0}
    videos = list(iter_videos(media_dirs))
    print(f"[scan] {len(videos)} videos in {len(media_dirs)} dir(s).")
    for v in videos:
        try:
            r = process_movie(v, tm, force=force, stt=stt)
        except Exception as e:
            print(f"[{v.stem}] failed: {e}", file=sys.stderr)
            r = "error"
        stats[r] = stats.get(r, 0) + 1
    tm.save()
    print("[scan] " + ", ".join(f"{k}={v}" for k, v in stats.items()))
    return stats


def main():
    ap = argparse.ArgumentParser(description="SubArabify Jellyfin addon (Termux + server).")
    ap.add_argument("--media", action="append", default=[],
                    help="media folder (repeatable). Default: $SUBARABIFY_MEDIA_DIR or /media")
    ap.add_argument("--once", action="store_true", help="single scan then exit")
    ap.add_argument("--watch", action="store_true", help="rescan every --interval minutes")
    ap.add_argument("--interval", type=int, default=60, help="watch interval in minutes (default 60)")
    ap.add_argument("--force", action="store_true", help="retranslate even if output exists")
    ap.add_argument("--stt", action="store_true",
                    help="transcribe English audio offline (faster-whisper) when no "
                         "subtitle file exists — WhisperSubs-style fallback. "
                         "First use downloads the whisper model once. "
                         "Also enabled via SUBARABIFY_STT=1.")
    ap.add_argument("--jellyfin-refresh", action="store_true",
                    help="trigger Jellyfin library refresh after scan (needs JELLYFIN_URL + JELLYFIN_API_KEY)")
    args = ap.parse_args()

    media = args.media or ([os.environ["SUBARABIFY_MEDIA_DIR"]] if os.environ.get("SUBARABIFY_MEDIA_DIR") else ["/media"])
    use_stt = args.stt or os.environ.get("SUBARABIFY_STT", "") == "1"
    # ⬇ model downloads HERE on enable — before touching any movie
    ensure_model()
    if args.watch:
        while True:
            scan(media, force=args.force, stt=use_stt)
            if args.jellyfin_refresh:
                jellyfin_refresh()
            print(f"[watch] sleeping {args.interval}m…")
            time.sleep(args.interval * 60)
    else:
        scan(media, force=args.force, stt=use_stt)
        if args.jellyfin_refresh:
            jellyfin_refresh()


if __name__ == "__main__":
    main()
