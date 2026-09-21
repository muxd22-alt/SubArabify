"""STT grouping tests (pure function, no model needed). Run: python test_stt.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stt import group_words


def test_groups_by_length():
    words = [{"start": i * 0.4, "end": i * 0.4 + 0.3, "word": w}
             for i, w in enumerate(
                 "hello world this is a much longer test of the grouping logic".split())]
    cues = group_words(words)
    assert len(cues) >= 2, cues
    for c in cues:
        assert c["end"] > c["start"], c
        assert len(c["text"]) <= 43, c


def test_splits_on_gap():
    words = [{"start": 1.0, "end": 1.3, "word": "hi"},
             {"start": 5.0, "end": 5.3, "word": "there"}]
    cues = group_words(words)
    assert len(cues) == 2, cues
    assert cues[0]["text"] == "hi" and cues[1]["text"] == "there"


def test_skips_empty():
    words = [{"start": 1.0, "end": 1.3, "word": "  "},
             {"start": 1.4, "end": 1.7, "word": "ok"}]
    cues = group_words(words)
    assert len(cues) == 1 and cues[0]["text"] == "ok", cues


def test_empty_in_empty_out():
    assert group_words([]) == []


if __name__ == "__main__":
    for name, fn in sorted({k: v for k, v in globals().items() if k.startswith("test_")}.items()):
        fn()
        print(f"PASS {name}")
    print("all stt tests passed")
