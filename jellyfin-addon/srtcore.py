"""Shared SRT core for SubArabify (v0.2.3-alpha).

Used by the Python client (client/) AND the unified Jellyfin addon
(jellyfin-addon/) — parity rules with
app/src/main/java/com/subarabify/data/SrtParser.kt.
This file is intentionally an identical copy of client/srtcore.py:
  * dialogue timecodes are NEVER modified — copied bit-identical
  * brand cues only go into real free gaps (never overlap dialogue)
  * filename always ends with .SubArabify.ar.srt
  * Arabic post-processing is light and safe (punctuation only)
"""

import re
import unicodedata

BRAND_LINE = "— SubArabify —"
APP_VERSION = "0.2.3-alpha"

THINKER_NOTE = """NOTE
SubArabify 0.2.3-alpha · for thinkers
We mark the edges. The middle stays free — your dialogue, uninterrupted.
Timings below are bit-identical to the source; only the brand cues are added.
If you are reading this, you already know why the filename says SubArabify.
"""

TIMECODE_RE = re.compile(
    r"\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}"
)
VTT_SETTING_RE = re.compile(r"\s+(align|position|size|vertical|line):\S+")
VTT_TIMECODE_RE = re.compile(
    r"\d{1,2}:\d{2}(?::\d{2})?[,.]\d{3}\s*-->\s*\d{1,2}:\d{2}(?::\d{2})?[,.]\d{3}"
)


def strip_markup(text: str) -> str:
    text = re.sub(r"\{[^}]*\}", "", text)  # {\an8}
    text = re.sub(r"<[^>]+>", "", text)
    for a, b in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">")):
        text = text.replace(a, b)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def split_prefix(line: str):
    """Return (prefix, core) shielding speaker labels from translation."""
    t = line.strip()
    if (t.startswith("[") and t.endswith("]")) or (
        t.startswith("(") and t.endswith(")")
    ) or t.startswith("♪") or t.endswith("♪"):
        return "", t
    m = re.match(r"^([A-ZÀ-Þ][A-ZÀ-Þ .'\-]{1,24}[:\-–—]\s+)(.+)$", t)
    if m:
        return m.group(1), m.group(2)
    return "", t


def is_sound_cue(line: str) -> bool:
    t = line.strip()
    if t.startswith("♪") or t.endswith("♪"):
        return True
    if t.startswith("[") or t.startswith("("):
        inner = t[1:-1].lower()
        return any(
            k in inner
            for k in ("music", "laugh", "applause", "sigh", "gasp", "موسيقى")
        ) or len(inner) <= 40
    return False


def parse(content: str):
    text = content.lstrip("\ufeff")
    if text.lstrip().lower().startswith("webvtt"):
        return parse_vtt(text)
    return parse_srt(text)


def parse_srt(content: str):
    lines = content.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks, i = [], 0
    while i < len(lines):
        raw = lines[i].strip()
        if not raw:
            i += 1
            continue
        if raw == "NOTE":
            i += 1
            while i < len(lines) and lines[i].strip() and not lines[i].strip().isdigit() \
                    and not TIMECODE_RE.search(lines[i]):
                i += 1
            continue
        if raw.isdigit():
            tc = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if not TIMECODE_RE.search(tc):
                i += 1
                continue
            i += 2
            timecode = tc.replace(".", ",")
        elif TIMECODE_RE.search(raw):
            timecode = raw.replace(".", ",")
            i += 1
        else:
            i += 1
            continue
        text_lines = []
        while i < len(lines) and lines[i].strip():
            c = strip_markup(lines[i])
            if c:
                text_lines.append(c)
            i += 1
        if text_lines:
            blocks.append({"index": str(len(blocks) + 1), "timecode": timecode, "lines": text_lines})
        i += 1
    return blocks


def _fix_vtt_ts(tc: str) -> str:
    def fix_one(p: str) -> str:
        p = p.strip().replace(".", ",")
        segs = p.split(":")
        if len(segs) == 2:
            return f"00:{segs[0].zfill(2)}:{segs[1]}"
        return p
    parts = tc.split("-->")
    if len(parts) != 2:
        return tc
    return f"{fix_one(parts[0])} --> {fix_one(parts[1])}"


def parse_vtt(content: str):
    lines = content.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks, i = [], 0
    while i < len(lines) and not VTT_TIMECODE_RE.search(lines[i]):
        i += 1
    while i < len(lines):
        raw = lines[i].strip()
        if not raw or raw.startswith(("NOTE", "STYLE", "REGION")):
            if raw.startswith(("NOTE", "STYLE", "REGION")):
                i += 1
                while i < len(lines) and lines[i].strip():
                    i += 1
            else:
                i += 1
            continue
        if VTT_TIMECODE_RE.search(raw):
            tc = VTT_SETTING_RE.sub("", raw).strip()
            i += 1
        else:
            nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if VTT_TIMECODE_RE.search(nxt):
                tc = VTT_SETTING_RE.sub("", nxt).strip()
                i += 2
            else:
                i += 1
                continue
        timecode = _fix_vtt_ts(tc)
        text_lines = []
        while i < len(lines) and lines[i].strip():
            line = re.sub(r"</?v[^>]*>", "", lines[i])
            c = strip_markup(line)
            if c:
                text_lines.append(c)
            i += 1
        if text_lines:
            blocks.append({"index": str(len(blocks) + 1), "timecode": timecode, "lines": text_lines})
        i += 1
    return blocks


def contains_arabic(s: str) -> bool:
    ar = sum(1 for c in s if "\u0600" <= c <= "\u06ff")
    lat = sum(1 for c in s if c.isascii() and c.isalpha())
    return ar > 0 and ar >= lat


def post_process_arabic(line: str) -> str:
    s = re.sub(r"\s+", " ", line).strip()
    if not s:
        return s
    if contains_arabic(s):
        s = s.replace("?", "؟").replace(";", "؛").replace(",", "،")
        s = re.sub(r"\s+([؟؛،:!.\-])", r"\1", s)
    s = re.sub(r"([؟!.\-]){2,}", lambda m: m.group(0)[:2], s)
    return s.strip()


def tc_to_ms(tc: str) -> int:
    parts = tc.replace(",", ":").replace(".", ":").split(":")
    if len(parts) < 4:
        return 0
    try:
        h, m, s, ms = (int(p) for p in parts[:4])
    except ValueError:
        return 0
    return h * 3600000 + m * 60000 + s * 1000 + ms


def ms_to_tc(ms: int) -> str:
    h, rem = divmod(max(0, ms), 3600000)
    m, rem = divmod(rem, 60000)
    s, milli = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{milli:03d}"


def safe_opening_brand(blocks):
    if not blocks:
        return {"index": "0", "timecode": "00:00:02,000 --> 00:00:08,000", "lines": [BRAND_LINE]}
    first_start = tc_to_ms(blocks[0]["timecode"].split("-->")[0])
    if first_start >= 3200:
        end = min(first_start - 400, 8000)
        end = max(end, 2000)
        start = max(end - 2000, 200)
        if end - start >= 1200:
            return {"index": "0", "timecode": f"{ms_to_tc(start)} --> {ms_to_tc(end)}",
                    "lines": [BRAND_LINE]}
    return None


def safe_closing_brand(blocks):
    if not blocks:
        return None
    last_end = tc_to_ms(blocks[-1]["timecode"].split("-->")[1])
    start = last_end + 1500
    return {"index": "0", "timecode": f"{ms_to_tc(start)} --> {ms_to_tc(start + 5000)}",
            "lines": [BRAND_LINE]}


def build_branded_srt(blocks, translated):
    core = []
    for idx, b in enumerate(blocks):
        lines = translated[idx] if idx < len(translated) else b["lines"]
        clean = [post_process_arabic(strip_markup(x)) for x in lines]
        clean = [c for c in clean if c]
        if not clean:
            continue
        core.append({"index": b["index"], "timecode": b["timecode"], "lines": clean})
    out = []
    opening = safe_opening_brand(core)
    if opening:
        out.append(opening)
    out.extend(core)
    closing = safe_closing_brand(core)
    if closing:
        out.append(closing)
    sb = [THINKER_NOTE, ""]
    for i, b in enumerate(out, start=1):
        sb.append(str(i))
        sb.append(b["timecode"])
        sb.extend(b["lines"])
        sb.append("")
    return "\n".join(sb)


# ── filename helpers (mirror StorageHelper.smartBase/fuzzyKey) ──

LANG_TAGS = re.compile(r"(?i)[._\- ](en|eng|english|en-us|en-gb|ar|arabic|arab)$")
BRAND_TAG = re.compile(r"(?i)[._\- ]?subarabify([._\- ]?ar)?$")
VIDEO_EXTS = {"mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv", "webm", "ts", "m2ts"}
SUB_EXTS = {"srt", "vtt"}
TAG_RE = re.compile(
    r"(?i)\b(1080p|720p|480p|2160p|4k|8k|web[\- ]?dl|webrip|bluray|blu[\- ]?ray|bdrip|"
    r"dvdrip|hdtv|hdr|hdr10|dolby|atmos|x264|x265|hevc|aac|dts|yts|yify|rarbg|"
    r"ettv|eztv|proper|repack|extended|unrated|remastered|multi|v2|final)\b"
)


def smart_base(filename: str) -> str:
    base = filename.rsplit(".", 1)[0] if "." in filename else filename
    base = LANG_TAGS.sub("", base)
    base = BRAND_TAG.sub("", base)
    return base


def fuzzy_key(name: str) -> str:
    s = smart_base(name)
    s = re.sub(r"[._\-]+", " ", s)
    s = re.sub(r"\s*[\(\[]\d{4}[\)\]]\s*", " ", s)
    s = re.sub(r"\s+\d{4}\s*", " ", s)
    s = TAG_RE.sub(" ", s)
    return re.sub(r"\s+", " ", s).strip().lower()


def is_ours(name: str) -> bool:
    l = name.lower()
    return "subarabify" in l or l.endswith(".ar.srt") or l.endswith(".ar.vtt")
