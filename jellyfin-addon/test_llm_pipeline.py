import json
import tempfile
from pathlib import Path
import pytest

import llm_translator
import whisper_cpp


def test_build_batch_prompt():
    items = {
        "1": "I didn't see that coming at all.",
        "2": "Keep your eyes on the road!"
    }
    prompt = llm_translator.build_batch_prompt(items)
    assert "<|im_start|>system" in prompt
    assert "<|im_start|>user" in prompt
    assert "I didn't see that coming at all." in prompt
    assert "<|im_start|>assistant" in prompt


def test_extract_json_from_output():
    raw_markdown = """
Here is the translation:
```json
{
  "1": "لم أتوقع حدوث ذلك على الإطلاق.",
  "2": "ركّز عينيك على الطريق!"
}
```
"""
    parsed = llm_translator.extract_json_from_output(raw_markdown)
    assert parsed["1"] == "لم أتوقع حدوث ذلك على الإطلاق."
    assert parsed["2"] == "ركّز عينيك على الطريق!"


def test_parse_whisper_json():
    sample_whisper = {
        "transcription": [
            {
                "id": 0,
                "offsets": {"from": 1000, "to": 3500},
                "text": "I didn't see that coming at all."
            },
            {
                "id": 1,
                "offsets": {"from": 3600, "to": 5200},
                "text": "Keep your eyes on the road!"
            }
        ]
    }
    with tempfile.NamedTemporaryFile(mode="w+", suffix=".json", delete=False) as f:
        json.dump(sample_whisper, f)
        temp_path = f.name

    try:
        cues = whisper_cpp.parse_whisper_json(temp_path)
        assert len(cues) == 2
        assert cues[0]["id"] == 0
        assert cues[0]["start"] == 1000
        assert cues[0]["text"] == "I didn't see that coming at all."
    finally:
        Path(temp_path).unlink(missing_ok=True)


def test_generate_arabic_srt_from_whisper_json(monkeypatch):
    # Mock batch_translate_subtitles to avoid requiring llama-cli executable in unit tests
    def mock_batch_translate(subtitle_items, llm_model_path, **kwargs):
        return {
            "0": "لم أتوقع حدوث ذلك على الإطلاق.",
            "1": "ركّز عينيك على الطريق!"
        }

    monkeypatch.setattr(llm_translator, "batch_translate_subtitles", mock_batch_translate)

    sample_whisper = {
        "transcription": [
            {
                "id": 0,
                "offsets": {"from": 1000, "to": 3500},
                "text": "I didn't see that coming at all."
            },
            {
                "id": 1,
                "offsets": {"from": 3600, "to": 5200},
                "text": "Keep your eyes on the road!"
            }
        ]
    }

    with tempfile.NamedTemporaryFile(mode="w+", suffix=".json", delete=False) as f_in:
        json.dump(sample_whisper, f_in)
        in_path = f_in.name

    with tempfile.NamedTemporaryFile(mode="w+", suffix=".srt", delete=False) as f_out:
        out_path = f_out.name

    try:
        llm_translator.generate_arabic_srt_from_whisper_json(
            in_path, out_path, "dummy_model.gguf"
        )
        content = Path(out_path).read_text(encoding="utf-8")
        assert "00:00:01,000 --> 00:00:03,500" in content
        assert "لم أتوقع حدوث ذلك على الإطلاق." in content
        assert "ركّز عينيك على الطريق!" in content
    finally:
        Path(in_path).unlink(missing_ok=True)
        Path(out_path).unlink(missing_ok=True)
