"""llama.cpp / Qwen 2.5 1.5B batch translation module for SubArabify (v1.0.3-beta).

Executes local llama.cpp wrapper (e.g. llama-cli) using Qwen 2.5 1.5B Instruct GGUF model
(Q4_K_M) with batched JSON prompts for high-throughput, contextual Arabic subtitle translation.
"""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import srtcore


SYSTEM_PROMPT = (
    "You are an expert subtitle translator for movies and TV shows.\n"
    "Translate the following subtitle lines into natural Arabic.\n"
    "Rules:\n"
    "1. Maintain the exact line ID key and output JSON only.\n"
    "2. Keep the tone natural and appropriate for screen dialogue.\n"
    "3. Do NOT translate proper names unless standard."
)


def find_llama_cli() -> str:
    cands = [
        os.environ.get("LLAMA_CLI_PATH", ""),
        "./llama-cli",
        "llama-cli",
        shutil.which("llama-cli") or "",
        "/usr/local/bin/llama-cli",
    ]
    for c in cands:
        if c and Path(c).exists():
            return c
    return "llama-cli"


def build_batch_prompt(subtitle_items: dict) -> str:
    """
    Constructs formatted ChatML prompt payload for Qwen 2.5 1.5B Instruct.
    """
    user_payload = json.dumps(subtitle_items, ensure_ascii=False, indent=2)
    prompt = (
        f"<|im_start|>system\n{SYSTEM_PROMPT}<|im_end|>\n"
        f"<|im_start|>user\n{user_payload}<|im_end|>\n"
        f"<|im_start|>assistant\n"
    )
    return prompt


def extract_json_from_output(raw_output: str) -> dict:
    """
    Parses LLM text output into JSON dictionary mapping line IDs to translated strings.
    Handles potential markdown code fences or extra trailing output.
    """
    s = raw_output.strip()
    if "```json" in s:
        s = s.split("```json", 1)[1].split("```", 1)[0].strip()
    elif "```" in s:
        s = s.split("```", 1)[1].split("```", 1)[0].strip()

    start_idx = s.find("{")
    end_idx = s.rfind("}")
    if start_idx != -1 and end_idx != -1 and end_idx > start_idx:
        s = s[start_idx : end_idx + 1]

    return json.loads(s)


def batch_translate_subtitles(
    subtitle_items: dict,
    llm_model_path: str,
    cli_path: str = None,
    ngl: int = 99,
    temp: float = 0.2,
    n_predict: int = 512,
) -> dict:
    """
    Passes batched dictionary to llama.cpp execution wrapper using Qwen 2.5 1.5B.
    """
    if cli_path is None:
        cli_path = find_llama_cli()

    prompt = build_batch_prompt(subtitle_items)

    cmd = [
        cli_path,
        "-m", llm_model_path,
        "-p", prompt,
        "-n", str(n_predict),
        "--temp", str(temp),
        "-ngl", str(ngl),
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    raw_output = result.stdout.strip()
    translated_json = extract_json_from_output(raw_output)
    return translated_json


def generate_arabic_srt_from_whisper_json(
    whisper_json_path: str,
    output_srt_path: str,
    llm_model_path: str,
    batch_size: int = 15,
    cli_path: str = None,
) -> str:
    """
    Batch processes whisper.cpp transcript JSON, translates in chunks of 15-20 lines
    via Qwen 2.5 1.5B / llama.cpp, and reconstructs a branded Arabic .srt file.
    """
    with open(whisper_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    raw_items = data.get("transcription", data.get("transcripts", []))
    transcripts = []
    for idx, item in enumerate(raw_items):
        item_id = item.get("id", idx)
        text = item.get("text", "").strip()
        offsets = item.get("offsets", {})
        start_ms = offsets.get("from", item.get("start", 0))
        end_ms = offsets.get("to", item.get("end", 0))

        transcripts.append({
            "id": item_id,
            "start": start_ms,
            "end": end_ms,
            "text": text,
        })

    blocks = []
    for item in transcripts:
        tc = f"{srtcore.ms_to_tc(int(item['start']))} --> {srtcore.ms_to_tc(int(item['end']))}"
        blocks.append({
            "index": str(item["id"] + 1 if isinstance(item["id"], int) else item["id"]),
            "timecode": tc,
            "lines": [item["text"]],
        })

    translated_lines = []
    for i in range(0, len(transcripts), batch_size):
        chunk = transcripts[i : i + batch_size]
        batch_dict = {str(item["id"]): item["text"] for item in chunk}

        try:
            translated_batch = batch_translate_subtitles(
                batch_dict, llm_model_path, cli_path=cli_path
            )
        except Exception as e:
            print(f"[llama] batch translation warning at chunk {i}: {e}", file=sys.stderr)
            translated_batch = batch_dict

        for item in chunk:
            item_id = str(item["id"])
            ar_text = translated_batch.get(item_id, item["text"])
            translated_lines.append([ar_text])

    content = srtcore.build_branded_srt(blocks, translated_lines)
    Path(output_srt_path).write_text(content, encoding="utf-8")
    return output_srt_path
