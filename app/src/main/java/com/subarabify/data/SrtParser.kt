package com.subarabify.data

data class SrtBlock(
    val index: String,
    val timecode: String,
    val textLines: List<String>
)

object SrtParser {

    // ── Two-tone HTML branding line (supported by most SRT renderers) ──
    private const val BRAND_LINE =
        "<font color=\"#F1F5F9\">Sub</font><font color=\"#F0A500\">Arabify</font>"

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
     * Builds the final .SubArabify.ar.srt content with:
     *  • Translated text lines wrapped in Thmanyah Sans font tags
     *  • Two-tone SubArabify branding at first 10s, a middle gap, and last 10s
     */
    fun buildBrandedSrt(
        blocks: List<SrtBlock>,
        translatedTexts: List<List<String>>,
    ): String {

        // 1 ── Build core translated blocks ──
        val coreBlocks = mutableListOf<SrtBlock>()
        for ((idx, block) in blocks.withIndex()) {
            val translated = translatedTexts.getOrNull(idx) ?: block.textLines
            val styledLines = translated.map { line ->
                "<font face=\"Thmanyah Sans\">$line</font>"
            }
            coreBlocks.add(SrtBlock(block.index, block.timecode, styledLines))
        }

        // 2 ── Insert branding blocks ──
        val branded = mutableListOf<SrtBlock>()

        // — Opening brand (first 10 seconds) —
        branded.add(
            SrtBlock("0", "00:00:02,000 --> 00:00:08,000", listOf(BRAND_LINE))
        )

        // — Core content —
        branded.addAll(coreBlocks)

        // — Middle brand (find a mid-point gap) —
        if (coreBlocks.size >= 4) {
            val midIdx = coreBlocks.size / 2
            val beforeEnd = parseEndMs(coreBlocks[midIdx - 1].timecode)
            val afterStart = parseStartMs(coreBlocks[midIdx].timecode)
            if (afterStart - beforeEnd > 1500) {
                // There is a gap — insert brand there
                val gapStart = beforeEnd + 200
                val gapEnd = afterStart - 200
                branded.add(
                    midIdx + 1, // +1 because of the opening brand we already added
                    SrtBlock("0", "${msToTimecode(gapStart)} --> ${msToTimecode(gapEnd)}", listOf(BRAND_LINE))
                )
            } else {
                // No gap — overlay briefly after mid block ends
                val overlayStart = beforeEnd + 100
                val overlayEnd = overlayStart + 3000
                branded.add(
                    midIdx + 1,
                    SrtBlock("0", "${msToTimecode(overlayStart)} --> ${msToTimecode(overlayEnd)}", listOf(BRAND_LINE))
                )
            }
        }

        // — Closing brand (last 10 seconds) —
        if (coreBlocks.isNotEmpty()) {
            val lastEnd = parseEndMs(coreBlocks.last().timecode)
            val closingStart = lastEnd + 1000
            val closingEnd = closingStart + 8000
            branded.add(
                SrtBlock("0", "${msToTimecode(closingStart)} --> ${msToTimecode(closingEnd)}", listOf(BRAND_LINE))
            )
        }

        // 3 ── Re-index and serialize ──
        val sb = StringBuilder()
        for ((i, block) in branded.withIndex()) {
            sb.append(i + 1).append("\n")
            sb.append(block.timecode).append("\n")
            block.textLines.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }
        return sb.toString()
    }

    /** Legacy non-branded builder (kept for compatibility) */
    fun buildSrt(blocks: List<SrtBlock>, translatedTexts: List<List<String>>): String {
        return buildBrandedSrt(blocks, translatedTexts)
    }

    // ── Timecode helpers ──────────────────────────────────────────

    /** Parse "00:01:23,456 --> 00:01:27,890" → start millis */
    private fun parseStartMs(timecode: String): Long {
        val start = timecode.split("-->").getOrNull(0)?.trim() ?: return 0L
        return timecodeToMs(start)
    }

    /** Parse "00:01:23,456 --> 00:01:27,890" → end millis */
    private fun parseEndMs(timecode: String): Long {
        val end = timecode.split("-->").getOrNull(1)?.trim() ?: return 0L
        return timecodeToMs(end)
    }

    private fun timecodeToMs(tc: String): Long {
        // "00:01:23,456"
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
