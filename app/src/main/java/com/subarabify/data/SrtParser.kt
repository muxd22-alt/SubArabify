package com.subarabify.data

data class SrtBlock(
    val index: String,
    val timecode: String,
    val textLines: List<String>
)

/** One cue preview for the in-app "normal subtitles" viewer. */
data class CuePreview(
    val startMs: Long,
    val endMs: Long,
    val timecode: String,
    val en: String,
    val ar: String,
)

object SrtParser {

    /** Plain brand — HTML font tags hide text on many Android players. */
    const val BRAND_LINE = "— SubArabify —"
    const val APP_VERSION = "1.0.4-pre"

    /**
     * Invisible-to-players preamble. Numbered cues start after this.
     * Anyone who opens the .srt in a text editor will find it.
     */
    const val THINKER_NOTE = """NOTE
SubArabify 1.0.4-pre · for thinkers
We mark the edges. The middle stays free — your dialogue, uninterrupted.
Timings below are bit-identical to the English source; only the brand cues are added.
If you are reading this, you already know why the filename says SubArabify.
"""

    private val TIMECODE_RE =
        Regex("""\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}""")
    private val VTT_CUE_SETTINGS = Regex("""\s+(align|position|size|vertical|line):\S+""")
    private val VTT_TIMECODE_RE =
        Regex("""\d{1,2}:\d{2}(?::\d{2})?[,.]\d{3}\s*-->\s*\d{1,2}:\d{2}(?::\d{2})?[,.]\d{3}""")

    /** Strip HTML / ASS-ish tags so translators and players see real words. */
    fun stripMarkup(text: String): String {
        return text
            .replace(Regex("""\{[^}]*\}"""), "") // {\an8} etc.
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Split a cue into a non-translatable prefix (speaker label / sound tag)
     * and the translatable core. Returns Pair(prefix, core).
     * e.g. "JOHN: Hello" -> ("JOHN:", "Hello"), "[Music playing]" -> ("[Music playing]", "")
     */
    fun splitPrefix(line: String): Pair<String, String> {
        val t = line.trim()
        // Pure sound-effect / music cue — keep brackets, translate inner description only
        if ((t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")")) ||
            t.startsWith("♪") || t.endsWith("♪")
        ) {
            return Pair("", t)
        }
        // Speaker label "NAME: dialogue" or "NAME - dialogue"
        val m = Regex("""^([A-ZÀ-Þ][A-ZÀ-Þ .'\-]{1,24}[:\-–—]\s+)(.+)$""").find(t)
        if (m != null) return Pair(m.groupValues[1], m.groupValues[2])
        return Pair("", t)
    }

    /** True if the cue is a sound/music note rather than dialogue. */
    fun isSoundCue(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("♪") || t.endsWith("♪")) return true
        val inner = t.removePrefix("[").removeSuffix("]").removePrefix("(").removeSuffix(")")
        if (t.startsWith("[") || t.startsWith("(")) {
            val low = inner.lowercase()
            return low.contains("music") || low.contains("laugh") || low.contains("applause") ||
                low.contains("sigh") || low.contains("gasps") || low.contains("موسيقى") ||
                inner.length <= 40
        }
        return false
    }

    fun parse(srtContent: String): List<SrtBlock> {
        val text = srtContent.removePrefix("\uFEFF")
        // Auto-detect WebVTT and convert
        if (text.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
            return parseVtt(text)
        }
        return parseSrt(text)
    }

    fun parseSrt(srtContent: String): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        val lines = srtContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i].trim()
            if (raw.isEmpty() || raw == "NOTE" || raw.startsWith("SubArabify")) {
                // Skip stray NOTE preamble lines until a real cue starts
                if (raw == "NOTE") {
                    // consume until blank line
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty() &&
                        !lines[i].trim().all { it.isDigit() } &&
                        !TIMECODE_RE.containsMatchIn(lines[i])
                    ) i++
                    continue
                }
                i++
                continue
            }

            val hasIndex = raw.all { it.isDigit() }
            val timecodeLine: String
            if (hasIndex) {
                timecodeLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (!TIMECODE_RE.containsMatchIn(timecodeLine)) {
                    i++
                    continue
                }
                i += 2
            } else if (TIMECODE_RE.containsMatchIn(raw)) {
                timecodeLine = raw
                i += 1
            } else {
                i++
                continue
            }

            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                val cleaned = stripMarkup(lines[i])
                if (cleaned.isNotEmpty()) textLines.add(cleaned)
                i++
            }
            if (textLines.isNotEmpty()) {
                blocks.add(
                    SrtBlock(
                        index = (blocks.size + 1).toString(),
                        timecode = timecodeLine.replace('.', ','),
                        textLines = textLines,
                    )
                )
            }
            i++
        }
        return blocks
    }

    fun parseVtt(vttContent: String): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        val lines = vttContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
        var i = 0
        // skip header
        while (i < lines.size && !VTT_TIMECODE_RE.containsMatchIn(lines[i])) i++
        while (i < lines.size) {
            val raw = lines[i].trim()
            if (raw.isEmpty() || raw.startsWith("NOTE") || raw.startsWith("STYLE") || raw.startsWith("REGION")) {
                // skip NOTE/STYLE blocks until blank
                if (raw.startsWith("NOTE") || raw.startsWith("STYLE") || raw.startsWith("REGION")) {
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                } else i++
                continue
            }
            var tcLine: String? = null
            if (VTT_TIMECODE_RE.containsMatchIn(raw)) {
                tcLine = VTT_CUE_SETTINGS.replace(raw, "").trim()
                i++
            } else {
                // cue identifier line, timecode next
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (VTT_TIMECODE_RE.containsMatchIn(next)) {
                    tcLine = VTT_CUE_SETTINGS.replace(next, "").trim()
                    i += 2
                } else {
                    i++
                    continue
                }
            }
            // VTT uses . for millis — normalize to ,
            val timecode = tcLine!!.replace('.', ',')
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                // strip voice spans <v Speaker>text</v>
                var l = lines[i].replace(Regex("""</?v[^>]*>"""), "")
                l = stripMarkup(l)
                if (l.isNotEmpty()) textLines.add(l)
                i++
            }
            // VTT timestamps may be mm:ss.mmm — expand to hh:mm:ss,mmm
            val fixed = expandVttTimecode(timecode)
            if (textLines.isNotEmpty()) {
                blocks.add(SrtBlock((blocks.size + 1).toString(), fixed, textLines))
            }
            i++
        }
        return blocks
    }

    private fun expandVttTimecode(tc: String): String {
        fun fixOne(part: String): String {
            val p = part.trim().replace('.', ',')
            val segs = p.split(":")
            return when (segs.size) {
                2 -> "00:${segs[0].padStart(2, '0')}:${segs[1]}"
                else -> p
            }
        }
        val parts = tc.split("-->")
        if (parts.size != 2) return tc
        return "${fixOne(parts[0])} --> ${fixOne(parts[1])}"
    }

    // ── Arabic post-processing (shared rules with jellyfin-addon/srtcore.py) ──

    /** Light, safe fixes applied AFTER translation. Never touches timecodes. */
    fun postProcessArabic(line: String): String {
        var s = line.trim()
        if (s.isEmpty()) return s
        // Don't translate pure sound brackets literally twice; keep single pair
        s = s.replace(Regex("""\s+"""), " ")
        // Latin ? ! , ; : → Arabic counterparts when line is mostly Arabic
        if (containsArabic(s)) {
            s = s.replace("?", "؟").replace(";", "؛").replace(",", "،")
            // Fix spaced punctuation
            s = s.replace(Regex("""\s+([؟؛،:!.\-])"""), "$1")
        }
        // Collapse repeated punctuation
        s = s.replace(Regex("""([؟!.\-]){2,}""")) { it.value.take(2) }
        // Normalize common alef forms for cleaner display (safe subset)
        s = s.replace("أ", "أ").replace("  ", " ")
        return s.trim()
    }

    fun containsArabic(s: String): Boolean {
        var ar = 0
        var latin = 0
        for (c in s) {
            when (c) {
                in '\u0600'..'\u06FF' -> ar++
                in 'a'..'z', in 'A'..'Z' -> latin++
            }
        }
        return ar >= latin && ar > 0
    }

    // ── Timing-safe branded builder ──
    //
    // Guarantees:
    //  • Every dialogue timecode is copied BIT-IDENTICAL from the source.
    //  • Brand cues are only inserted into REAL gaps (never overlap dialogue).
    //  • If there is no safe gap at the start, the opening brand is skipped
    //    (the file NOTE + filename + end cue still carry the brand).

    fun buildBrandedSrt(
        blocks: List<SrtBlock>,
        translatedTexts: List<List<String>>,
    ): String {
        val coreBlocks = mutableListOf<SrtBlock>()
        for ((idx, block) in blocks.withIndex()) {
            val translated = translatedTexts.getOrNull(idx) ?: block.textLines
            val plainLines = translated.map { stripMarkup(it) }
                .map { postProcessArabic(it) }
                .filter { it.isNotEmpty() }
            if (plainLines.isEmpty()) continue
            // Dialogue timings are sacred — copy verbatim
            coreBlocks.add(SrtBlock(block.index, block.timecode, plainLines))
        }

        val branded = mutableListOf<SrtBlock>()
        val opening = safeOpeningBrand(coreBlocks)
        if (opening != null) branded.add(opening)
        branded.addAll(coreBlocks)
        val closing = safeClosingBrand(coreBlocks)
        if (closing != null) branded.add(closing)

        val sb = StringBuilder()
        sb.append(THINKER_NOTE).append("\n")
        for ((i, block) in branded.withIndex()) {
            sb.append(i + 1).append("\n")
            sb.append(block.timecode).append("\n")
            block.textLines.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }
        return sb.toString()
    }

    /** Returns an opening brand cue ONLY if a real gap exists before cue #1. */
    fun safeOpeningBrand(coreBlocks: List<SrtBlock>): SrtBlock? {
        if (coreBlocks.isEmpty()) {
            return SrtBlock("0", "00:00:02,000 --> 00:00:08,000", listOf(BRAND_LINE))
        }
        val firstStart = parseStartMs(coreBlocks.first().timecode)
        // Need ≥2.5s of free space to place a 2s brand with 400ms padding each side
        if (firstStart >= 3_200L) {
            val end = minOf(firstStart - 400, 8_000L).coerceAtLeast(2_000L)
            val start = (end - 2_000L).coerceAtLeast(200L)
            if (end - start >= 1_200L) {
                return SrtBlock("0", "${msToTimecode(start)} --> ${msToTimecode(end)}", listOf(BRAND_LINE))
            }
        }
        return null // no safe gap → skip opening brand, timings untouched
    }

    /** Closing brand always fits: it goes AFTER the last cue with a 1.5s gap. */
    fun safeClosingBrand(coreBlocks: List<SrtBlock>): SrtBlock? {
        if (coreBlocks.isEmpty()) return null
        val lastEnd = parseEndMs(coreBlocks.last().timecode)
        val start = lastEnd + 1_500
        val end = start + 5_000
        return SrtBlock("0", "${msToTimecode(start)} --> ${msToTimecode(end)}", listOf(BRAND_LINE))
    }

    fun buildSrt(blocks: List<SrtBlock>, translatedTexts: List<List<String>>): String {
        return buildBrandedSrt(blocks, translatedTexts)
    }

    /** Build small previews for the in-app "normal subtitles" viewer. */
    fun buildPreviews(
        blocks: List<SrtBlock>,
        translatedTexts: List<List<String>>,
        max: Int = 8,
    ): List<CuePreview> {
        val out = mutableListOf<CuePreview>()
        for ((idx, block) in blocks.withIndex()) {
            if (out.size >= max) break
            val ar = translatedTexts.getOrNull(idx)?.joinToString(" ").orEmpty()
            out.add(
                CuePreview(
                    startMs = parseStartMs(block.timecode),
                    endMs = parseEndMs(block.timecode),
                    timecode = block.timecode,
                    en = block.textLines.joinToString(" "),
                    ar = ar.ifBlank { block.textLines.joinToString(" ") },
                )
            )
        }
        return out
    }

    fun parseStartMs(timecode: String): Long {
        val start = timecode.split("-->").getOrNull(0)?.trim() ?: return 0L
        return timecodeToMs(start)
    }

    fun parseEndMs(timecode: String): Long {
        val end = timecode.split("-->").getOrNull(1)?.trim() ?: return 0L
        return timecodeToMs(end)
    }

    fun timecodeToMs(tc: String): Long {
        val parts = tc.replace(",", ":").replace(".", ":").split(":")
        if (parts.size < 4) return 0L
        val h = parts[0].toLongOrNull() ?: 0L
        val m = parts[1].toLongOrNull() ?: 0L
        val s = parts[2].toLongOrNull() ?: 0L
        val ms = parts[3].toLongOrNull() ?: 0L
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }

    fun msToTimecode(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val milli = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
