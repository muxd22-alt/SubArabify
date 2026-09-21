package com.subarabify.data

data class SrtBlock(
    val index: String,
    val timecode: String,
    val textLines: List<String>
)

object SrtParser {

    /** Plain brand — HTML font tags hide text on many Android players. */
    private const val BRAND_LINE = "— SubArabify —"

    /**
     * Invisible-to-players preamble. Numbered cues start after this.
     * Anyone who opens the .srt in a text editor will find it.
     */
    private const val THINKER_NOTE = """NOTE
SubArabify · for thinkers
We mark the edges. The middle stays free — your dialogue, uninterrupted.
If you are reading this, you already know why the filename says SubArabify.
"""

    private val TIMECODE_RE =
        Regex("""\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}""")

    /** Strip HTML / ASS-ish tags so ML Kit and players see real words. */
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

    fun parse(srtContent: String): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        // Normalize BOM + newlines
        val lines = srtContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i].trim()
            if (raw.isEmpty()) {
                i++
                continue
            }

            // Accept "1" or "1 " as index; also allow timecode-first blocks (no index)
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

    /**
     * Builds a player-safe Arabic `.srt`:
     *  • Opening brand near the start
     *  • Free middle — plain translated dialogue (no font tags)
     *  • Closing brand after the last cue
     *  • Hidden NOTE at the top for thinkers
     */
    fun buildBrandedSrt(
        blocks: List<SrtBlock>,
        translatedTexts: List<List<String>>,
    ): String {

        val coreBlocks = mutableListOf<SrtBlock>()
        for ((idx, block) in blocks.withIndex()) {
            val translated = translatedTexts.getOrNull(idx) ?: block.textLines
            val plainLines = translated.map { stripMarkup(it) }.filter { it.isNotEmpty() }
            if (plainLines.isEmpty()) continue
            coreBlocks.add(SrtBlock(block.index, block.timecode, plainLines))
        }

        val branded = mutableListOf<SrtBlock>()

        branded.add(SrtBlock("0", openingBrandTimecode(coreBlocks), listOf(BRAND_LINE)))
        branded.addAll(coreBlocks)

        if (coreBlocks.isNotEmpty()) {
            val lastEnd = parseEndMs(coreBlocks.last().timecode)
            val closingStart = lastEnd + 1000
            val closingEnd = closingStart + 8000
            branded.add(
                SrtBlock("0", "${msToTimecode(closingStart)} --> ${msToTimecode(closingEnd)}", listOf(BRAND_LINE))
            )
        }

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

    private fun openingBrandTimecode(coreBlocks: List<SrtBlock>): String {
        val firstStart = if (coreBlocks.isNotEmpty()) {
            parseStartMs(coreBlocks.first().timecode)
        } else {
            10_000L
        }

        return if (firstStart >= 8_500L) {
            "00:00:02,000 --> 00:00:08,000"
        } else if (firstStart >= 3_000L) {
            val end = firstStart - 400
            "00:00:00,500 --> ${msToTimecode(end)}"
        } else {
            "00:00:00,200 --> 00:00:02,000"
        }
    }

    fun buildSrt(blocks: List<SrtBlock>, translatedTexts: List<List<String>>): String {
        return buildBrandedSrt(blocks, translatedTexts)
    }

    private fun parseStartMs(timecode: String): Long {
        val start = timecode.split("-->").getOrNull(0)?.trim() ?: return 0L
        return timecodeToMs(start)
    }

    private fun parseEndMs(timecode: String): Long {
        val end = timecode.split("-->").getOrNull(1)?.trim() ?: return 0L
        return timecodeToMs(end)
    }

    private fun timecodeToMs(tc: String): Long {
        val parts = tc.replace(",", ":").replace(".", ":").split(":")
        if (parts.size < 4) return 0L
        val h = parts[0].toLongOrNull() ?: 0L
        val m = parts[1].toLongOrNull() ?: 0L
        val s = parts[2].toLongOrNull() ?: 0L
        val ms = parts[3].toLongOrNull() ?: 0L
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }

    private fun msToTimecode(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val milli = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
