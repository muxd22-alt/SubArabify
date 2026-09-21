"""Self-tests for srtcore (run: python -m pytest test_srtcore.py -q, or python test_srtcore.py)."""
import srtcore

EN = """1
00:00:05,000 --> 00:00:07,000
Hello. Previously on…

2
00:00:08,000 --> 00:00:10,500
JOHN: Come on, let's go!

3
00:00:11,000 --> 00:00:13,000
[Music playing]

4
00:01:30,000 --> 00:01:32,000
What?
"""

VTT = """WEBVTT

00:05.000 --> 00:07.000 align:start
Hello.

note-1
00:08.000 --> 00:10.000
JOHN: Come on!
"""


def test_parse_srt():
    blocks = srtcore.parse(EN)
    assert len(blocks) == 4, blocks
    assert blocks[0]["timecode"] == "00:00:05,000 --> 00:00:07,000"


def test_parse_vtt():
    blocks = srtcore.parse(VTT)
    assert len(blocks) == 2, blocks
    assert blocks[0]["timecode"].startswith("00:00:05,000"), blocks[0]


def test_split_prefix():
    assert srtcore.split_prefix("JOHN: Come on") == ("JOHN: ", "Come on")
    assert srtcore.split_prefix("[Music playing]") == ("", "[Music playing]")


def test_arabic_post():
    assert srtcore.post_process_arabic("مرحبا , كيف ؟") == "مرحبا، كيف؟"


def test_brand_never_overlaps():
    blocks = srtcore.parse(EN)
    ar = [["مرحبًا."] for _ in blocks]
    out = srtcore.build_branded_srt(blocks, ar)
    # dialogue timecodes bit-identical
    for b in blocks:
        assert b["timecode"] in out, b["timecode"]
    # opening brand fits in 0..5s gap (no overlap)
    assert "— SubArabify —" in out
    # closing brand after last cue end + gap
    last_end = srtcore.tc_to_ms(blocks[-1]["timecode"].split("-->")[1])
    closing_start = last_end + 1500
    assert srtcore.ms_to_tc(closing_start) in out


def test_brand_skipped_when_no_gap():
    crowded = """1
00:00:00,500 --> 00:00:02,500
Hello.

2
00:00:02,600 --> 00:00:04,000
Hi.
"""
    blocks = srtcore.parse(crowded)
    opening = srtcore.safe_opening_brand(
        [{"index": "1", "timecode": b["timecode"], "lines": b["lines"]} for b in blocks]
    )
    assert opening is None  # no safe gap → timings untouched


def test_fuzzy():
    assert srtcore.fuzzy_key("Movie.2023.1080p.WEB-DL.mkv") == srtcore.fuzzy_key("movie_en.srt")
    assert srtcore.smart_base("Dune.en.srt".rsplit(".", 1)[0]) == "Dune"


if __name__ == "__main__":
    for name, fn in sorted({k: v for k, v in globals().items() if k.startswith("test_")}.items()):
        fn()
        print(f"PASS {name}")
    print("all srtcore tests passed")
