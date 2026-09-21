# SubArabify Architecture & On-Device Auto-Translation Strategy

To achieve cutting-edge, fast, and fully offline video/movie auto-translation directly on a phone or local server, SubArabify supports a high-throughput 2-stage local pipeline architecture:

## 1. Local Pipeline Architecture

```
[ Local Video / Audio Media ]
             │
             ▼
[ whisper.cpp ] ── (Extracts timestamps & transcript into SRT/JSON chunks via whisper-cli)
             │
             ▼
[ Batch Processor ] ── (Groups 15–20 subtitle lines into 1 ChatML prompt)
             │
             ▼
[ llama.cpp / Qwen 2.5 1.5B ] ── (Translates batch via GGUF Q4_K_M while maintaining line IDs & timings)
             │
             ▼
[ Clean SubArabify Output (.srt / .vtt) ]
```

## 2. Tiny/Mobile LLM Benchmark & Model Selection (4-bit Quantized GGUF Q4_K_M)

| Model | Size (RAM Footprint) | Inference Speed (Flagship Phone) | Translation Quality (Arabic / Subtitles) |
|---|---|---|---|
| **Qwen 2.5 1.5B Instruct (Top Choice)** | **~1.1 GB** | **45–60 tokens/sec** | **Exceptional.** Understands idioms, informal movie dialogue, and context shifts better than any model under 3B. |
| **Qwen 2.5 3B Instruct** | ~2.0 GB | 25–35 tokens/sec | Best Quality. Nuanced localized translation, handles slang and movie tone accurately. |
| **Gemma 3 1B / Gemma 2 2B** | ~0.9 - 1.4 GB | 50–70 tokens/sec | Ultra Fast. Highly optimized for ARM NEON/NPUs, but slightly more literal in Arabic subtitle phrasing. |
| **Llama 3.2 1B Instruct** | ~0.8 GB | 60–80 tokens/sec | Lightning Speed. Great for basic translation, though may struggle with complex Arabic grammar in continuous streams. |

> **Recommendation**: Qwen 2.5 1.5B Instruct (GGUF Q4_K_M) offers the ideal balance of fast execution speed and contextual accuracy for Arabic subtitle translation.

## 3. High-Speed Batch Prompt Template

Batches of 15–20 SRT entries inside a strict JSON ChatML format reduce prompt processing overhead by up to 80%:

### System Prompt
```
You are an expert subtitle translator for movies and TV shows.
Translate the following subtitle lines into natural Arabic.
Rules:
1. Maintain the exact line ID key and output JSON only.
2. Keep the tone natural and appropriate for screen dialogue.
3. Do NOT translate proper names unless standard.
```

### User Input (Batch Payload)
```json
{
  "1": "I didn't see that coming at all.",
  "2": "Keep your eyes on the road!",
  "3": "We need to get out of here right now."
}
```

### Model Output
```json
{
  "1": "لم أتوقع حدوث ذلك على الإطلاق.",
  "2": "ركّز عينيك على الطريق!",
  "3": "علينا الخروج من هنا فوراً."
}
```

## 4. Hardware Acceleration & Mobile Optimization Checklist

- **GPU/NPU Offloading**:
  - Android: Compile llama.cpp with Vulkan support (`-DLLAMA_VULKAN=ON`).
  - iOS / macOS: Compile llama.cpp with Metal support enabled (`-DLLAMA_METAL=ON`).
- **Audio Pre-processing**: Extract mono 16kHz WAV audio (`pcm_s16le`) with ffmpeg or MediaCodec before feeding audio to whisper.cpp.
- **Whisper Model Choice**: Use `ggml-small.bin` or `ggml-base.bin` (`tiny` is fast but can lose context in noisy movie background audio).
