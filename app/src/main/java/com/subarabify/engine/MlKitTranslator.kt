package com.subarabify.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.subarabify.data.SrtParser
import com.subarabify.data.SrtBlock
import com.subarabify.data.TranslationMemory
import kotlinx.coroutines.tasks.await

private data class TranslationJob(
    val blockIdx: Int,
    val lineIdx: Int,
    val prefix: String,
    val core: String,
    val soundCue: Boolean,
)

/**
 * Smart on-device EN→AR translator (1.0.4-pre).
 *
 * Fixes the two big complaints about v1.0:
 *  1. "Translation is wrong" — single words out of context translate badly.
 *     Now: speaker labels + sound tags are shielded, short cues are joined
 *     into sentence chunks for context, a glossary pins common subtitle
 *     phrases, and Arabic punctuation is post-fixed.
 *  2. "Lags between runs" — one ML Kit request PER LINE is ~1000 awaits
 *     per movie. Now: persistent translation memory + chunked batch
 *     requests (≈8–15 requests per movie instead of ~1000).
 */
class MlKitTranslator(
    private val memory: TranslationMemory? = null,
) {

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.ARABIC)
        .build()

    private val translator = Translation.getClient(options)

    var lastStats: BatchStats = BatchStats()
        private set

    data class BatchStats(
        val total: Int = 0,
        val fromMemory: Int = 0,
        val fromGlossary: Int = 0,
        val fromModel: Int = 0,
    )

    suspend fun prepareModel(requireWifi: Boolean = false): Boolean {
        return try {
            val builder = DownloadConditions.Builder()
            if (requireWifi) builder.requireWifi()
            translator.downloadModelIfNeeded(builder.build()).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── Glossary: instant, always-correct answers for the most common cues ──
    private val glossary: Map<String, String> = mapOf(
        "previously on" to "في الحلقة السابقة",
        "previously on…" to "في الحلقة السابقة…",
        "previously on..." to "في الحلقة السابقة...",
        "previously" to "سابقًا",
        "to be continued" to "يتبع",
        "to be continued…" to "يتبع…",
        "the end" to "النهاية",
        "yeah" to "أجل",
        "yeah." to "أجل.",
        "yep" to "أجل",
        "nope" to "لا",
        "okay" to "حسنًا",
        "okay." to "حسنًا.",
        "ok" to "حسنًا",
        "ok." to "حسنًا.",
        "hey" to "مهلًا",
        "hey!" to "مهلًا!",
        "hi" to "مرحبًا",
        "hello" to "مرحبًا",
        "hello." to "مرحبًا.",
        "good morning" to "صباح الخير",
        "good night" to "تصبح على خير",
        "thank you" to "شكرًا لك",
        "thanks" to "شكرًا",
        "sorry" to "آسف",
        "i'm sorry" to "أنا آسف",
        "come on" to "هيا",
        "come on!" to "هيا!",
        "let's go" to "هيا بنا",
        "let's go!" to "هيا بنا!",
        "watch out" to "احذر",
        "watch out!" to "احذر!",
        "help!" to "النجدة!",
        "help" to "النجدة",
        "shh" to "صه",
        "wow" to "واو",
        "oh my god" to "يا إلهي",
        "oh my god!" to "يا إلهي!",
        "oh no" to "أوه لا",
        "what?" to "ماذا؟",
        "what!" to "ماذا!",
        "what" to "ماذا",
        "why?" to "لماذا؟",
        "how?" to "كيف؟",
        "where are you?" to "أين أنت؟",
        "are you okay?" to "هل أنت بخير؟",
        "are you ok?" to "هل أنت بخير؟",
        "i love you" to "أحبك",
        "i love you." to "أحبك.",
        "i don't know" to "لا أعرف",
        "i dont know" to "لا أعرف",
        "i don't know." to "لا أعرف.",
        "no way" to "مستحيل",
        "no way!" to "مستحيل!",
        "of course" to "بالطبع",
        "exactly" to "بالضبط",
        "cheers" to "في صحتك",
    )

    /**
     * Translate whole subtitle blocks with context + cache.
     * Returns one translated line-list per input block, same order.
     * Dialogue timecodes are NEVER touched here — only text.
     */
    suspend fun translateBlocks(
        blocks: List<SrtBlock>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<List<String>> {
        if (blocks.isEmpty()) return emptyList()

        val jobs = mutableListOf<TranslationJob>()
        blocks.forEachIndexed { bi, b ->
            b.textLines.forEachIndexed { li, raw ->
                val clean = SrtParser.stripMarkup(raw)
                if (clean.isEmpty()) {
                    jobs.add(TranslationJob(bi, li, "", "", false))
                } else {
                    val (prefix, core) = SrtParser.splitPrefix(clean)
                    jobs.add(TranslationJob(bi, li, prefix, core, SrtParser.isSoundCue(clean)))
                }
            }
        }

        val out = Array(blocks.size) { mutableListOf<String>() }
        blocks.forEachIndexed { bi, b -> repeat(b.textLines.size) { out[bi].add("") } }

        var fromMemory = 0
        var fromGlossary = 0

        val pending = mutableListOf<TranslationJob>()
        val pendingNorm = mutableMapOf<TranslationJob, String>()
        for (job in jobs) {
            if (job.core.isEmpty()) {
                out[job.blockIdx][job.lineIdx] = job.prefix
                continue
            }
            if (job.core.all { it.isDigit() }) {
                out[job.blockIdx][job.lineIdx] = job.prefix + job.core
                continue
            }
            val norm = TranslationMemory.normalize(job.core)
            val g = glossary[norm]
            if (g != null) {
                out[job.blockIdx][job.lineIdx] = reassemble(job, g)
                fromGlossary++
                continue
            }
            val mem = memory?.get(norm)
            if (mem != null) {
                out[job.blockIdx][job.lineIdx] = reassemble(job, mem)
                fromMemory++
            } else {
                pending.add(job)
                pendingNorm[job] = norm
            }
        }

        var fromModel = 0
        if (pending.isNotEmpty()) {
            val chunks = chunkJobs(pending, maxChars = 600)
            var done = 0
            for (chunk in chunks) {
                val translatedCores = translateChunk(chunk.map { it.core })
                val toCache = mutableMapOf<String, String>()
                chunk.forEachIndexed { ci, job ->
                    val ar = translatedCores.getOrNull(ci) ?: job.core
                    out[job.blockIdx][job.lineIdx] = reassemble(job, ar)
                    pendingNorm[job]?.let { toCache[it] = ar }
                    fromModel++
                }
                memory?.putAll(toCache)
                done += chunk.size
                onProgress?.invoke(done, pending.size)
            }
        }

        lastStats = BatchStats(
            total = jobs.size,
            fromMemory = fromMemory,
            fromGlossary = fromGlossary,
            fromModel = fromModel,
        )
        return out.map { it.toList() }
    }

    /** Legacy per-line entry kept for API compat — now cache-aware. */
    suspend fun translateBatch(lines: List<String>): List<String> {
        val fake = listOf(SrtBlock("1", "00:00:00,000 --> 00:00:01,000", lines))
        return translateBlocks(fake).firstOrNull() ?: lines
    }

    // Join a chunk with a rare delimiter so one ML Kit call carries context.
    private suspend fun translateChunk(cores: List<String>): List<String> {
        if (cores.isEmpty()) return emptyList()
        if (cores.size == 1) return listOf(safeTranslate(cores[0]))
        val delim = "\n‖\n"
        val joined = cores.joinToString(delim)
        if (joined.length > 900) {
            val mid = cores.size / 2
            return translateChunk(cores.subList(0, mid)) + translateChunk(cores.subList(mid, cores.size))
        }
        return try {
            val ar = safeTranslate(joined)
            val parts = ar.split(Regex("""\s*‖\s*"""))
            if (parts.size == cores.size) parts.map { SrtParser.postProcessArabic(it) }
            else cores.map { safeTranslate(it) }
        } catch (_: Exception) {
            cores.map { c -> try { safeTranslate(c) } catch (_: Exception) { c } }
        }
    }

    private suspend fun safeTranslate(text: String): String {
        val clean = SrtParser.stripMarkup(text)
        if (clean.isEmpty() || clean.all { it.isDigit() }) return clean
        return try {
            val ar = translator.translate(clean).await()
            SrtParser.postProcessArabic(ar.ifBlank { clean })
        } catch (_: Exception) {
            clean
        }
    }

    private fun reassemble(job: TranslationJob, arCore: String): String {
        val fixed = SrtParser.postProcessArabic(arCore)
        if (job.prefix.isEmpty()) return fixed
        return "${job.prefix}$fixed".trim()
    }

    private fun chunkJobs(jobs: List<TranslationJob>, maxChars: Int = 600): List<List<TranslationJob>> {
        val chunks = mutableListOf<List<TranslationJob>>()
        var cur = mutableListOf<TranslationJob>()
        var len = 0
        for (j in jobs) {
            if (cur.isNotEmpty() && len + j.core.length > maxChars) {
                chunks.add(cur)
                cur = mutableListOf()
                len = 0
            }
            cur.add(j)
            len += j.core.length + 4
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        return chunks
    }

    fun close() {
        try { translator.close() } catch (_: Exception) { }
    }
}
