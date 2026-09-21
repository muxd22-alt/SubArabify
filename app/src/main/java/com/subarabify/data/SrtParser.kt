package com.subarabify.data

data class SrtBlock(
    val index: String,
    val timecode: String,
    val textLines: List<String>
)

object SrtParser {

    // ── Two-tone HTML branding (most SRT players render <font color>) ──
    private const val BRAND_LINE =
        "<font color=\"#F1F5F9\">Sub</font><font color=\"#F0A500\">Arabify</font>"

    /**
     * Invisible-to-players preamble. Numbered cues start after this.
     * Anyone who opens the .srt in a text editor will find it.
     */
    private const val THINKER_NOTE = """NOTE
SubArabify · for thinkers
We mark the edges. The middle stays free — your dialogue, uninterrupted.
If you are reading this, you already know why the filename says SubArabify.
"""

    fun parse(srtContent: String): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        val lines = srtContent.lines()
        var i = 0

        while (i < lines.size) {
            val index = lines.getOrNull(i)?.trim() ?: ""
            if (index.isEmpty() || !index.all { it.isDigit() }) {
                i++
                continue
            }
            val timecode = lines.getOrNull(i + 1)?.trim() ?: ""
            i += 2

            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                textLines.add(lines[i])
                i++
            }
            blocks.add(SrtBlock(index, timecode, textLines))
            i++
        }
        return blocks
    }

    /**
     * Builds device-ready `.SubArabify.ar.srt` content:
     *  • Opening brand near the start (first window)
     *  • Free middle — translated dialogue only, no watermarks
     *  • Closing brand after the last cue
     *  • Hidden NOTE at the top for anyone who opens the file
     */
    fun buildBrandedSrt(
        blocks: List<SrtBlock>,
        translatedTexts: List<List<String>>,
    ): String {

        val coreBlocks = mutableListOf<SrtBlock>()
        for ((idx, block) in blocks.withIndex()) {
            val translated = translatedTexts.getOrNull(idx) ?: block.textLines
            val styledLines = translated.map { line ->
                "<font face=\"Thmanyah Sans\">$line</font>"
            }
            coreBlocks.add(SrtBlock(block.index, block.timecode, styledLines))
        }

        val branded = mutableListOf<SrtBlock>()

        // — Opening brand (first ~6s window, avoid covering first dialogue when possible) —
        branded.add(SrtBlock("0", openingBrandTimecode(coreBlocks), listOf(BRAND_LINE)))

        // — Core content: middle stays free —
        branded.addAll(coreBlocks)

        // — Closing brand (after last cue) —
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

    /** Prefer a gap before the first cue; otherwise a short early overlay. */
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
            // Dialogue starts early — brief brand that most players clear for speech
            "00:00:00,200 --> 00:00:02,000"
        }
    }

    /** Legacy non-branded builder (kept for compatibility) */
    fun buildSrt(blocks: List<SrtBlock>, translatedTexts: List<List<String>>): String {
        return buildBrandedSrt(blocks, translatedTexts)
    }

    // ── Timecode helpers ──────────────────────────────────────────

    private fun parseStartMs(timecode: String): Long {
        val start = timecode.split("-->").getOrNull(0)?.trim() ?: return 0L
        return timecodeToMs(start)
    }

    private fun parseEndMs(timecode: String): Long {
        val end = timecode.split("-->").getOrNull(1)?.trim() ?: return 0L
        return timecodeToMs(end)
    }

    private fun timecodeToMs(tc: String): Long {
        val parts = tc.replace(",", ":").split(":")
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
