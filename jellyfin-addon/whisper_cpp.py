"""whisper.cpp engine wrapper for SubArabify (v1.0.4-pre).

Executes local whisper.cpp binary (e.g. whisper-cli) to transcribe audio/video
into JSON formatted transcript with timestamps.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def find_whisper_cli() -> str:
    cands = [
        os.environ.get("WHISPER_CLI_PATH", ""),
        "./whisper-cli",
        "whisper-cli",
        shutil.which("whisper-cli") or "",
        "/usr/local/bin/whisper-cli",
    ]
    for c in cands:
        if c and Path(c).exists():
            return c
    return "whisper-cli"


def extract_wav_16k(video_or_audio_path: str, output_wav_path: str, ffmpeg_bin: str = "ffmpeg") -> str:
    """Pre-processes input media into 16kHz mono WAV suitable for whisper.cpp."""
    cmd = [
        ffmpeg_bin,
        "-nostdin",
        "-y",
        "-v", "error",
        "-i", video_or_audio_path,
        "-ar", "16000",
        "-ac", "1",
        "-c:a", "pcm_s16le",
        output_wav_path,
    ]
    subprocess.run(cmd, check=True, capture_output=True)
    return output_wav_path


def transcribe_audio_whisper_cpp(
    audio_path: str,
    model_path: str,
    cli_path: str = None,
    threads: int = 4,
    ffmpeg_bin: str = "ffmpeg",
) -> str:
    """
    Runs local whisper.cpp binary to generate JSON transcript file.
    Returns path to generated JSON file.
    """
    if cli_path is None:
        cli_path = find_whisper_cli()

    audio_p = Path(audio_path)
    # Check if pre-processing to 16kHz mono WAV is needed
    temp_wav = None
    if audio_p.suffix.lower() != ".wav":
        temp_dir = tempfile.mkdtemp(prefix="subarabify-whisper-")
        temp_wav = str(Path(temp_dir) / "audio_16k.wav")
        extract_wav_16k(audio_path, temp_wav, ffmpeg_bin=ffmpeg_bin)
        target_audio = temp_wav
    else:
        target_audio = audio_path

    cmd = [
        cli_path,
        "-m", model_path,
        "-f", target_audio,
        "-ojf",
        "-t", str(threads),
    ]

    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        # whisper.cpp creates <target_audio>.json
        out_json_path = f"{target_audio}.json"
        if not Path(out_json_path).exists():
            # fallback if whisper-cli outputs to audio_path.json
            out_json_path = f"{audio_path}.json"
        return out_json_path
    finally:
        # Note: caller or temporary directory handles cleanup
        pass


def parse_whisper_json(json_path: str) -> list:
    """
    Parses whisper.cpp JSON output file into a normalized list of subtitle cue dicts:
    [{'id': 0, 'start': 0, 'end': 1000, 'text': '...'}]
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    raw_items = data.get("transcription", data.get("transcripts", []))
    cues = []
    for idx, item in enumerate(raw_items):
        item_id = item.get("id", idx)
        text = item.get("text", "").strip()
        # timestamps in whisper.cpp json can be in ms or offsets dict
        offsets = item.get("offsets", {})
        start_ms = offsets.get("from", item.get("start", 0))
        end_ms = offsets.get("to", item.get("end", 0))

        cues.append({
            "id": item_id,
            "start": start_ms,
            "end": end_ms,
            "text": text,
        })
    return cues
