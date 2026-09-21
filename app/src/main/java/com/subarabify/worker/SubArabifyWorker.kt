package com.subarabify.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.subarabify.data.SrtParser
import com.subarabify.data.StorageHelper
import com.subarabify.data.TranslationMemory
import com.subarabify.engine.MlKitTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart background worker (1.0.3-beta):
 *  1. Scans the user-selected media folder recursively
 *  2. For each video (or a single TARGET_BASE):
 *     a) Output exists → DONE (unless FORCE_RETRANSLATE)
 *     b) Skip-listed → SKIPPED
 *     c) Smart source resolver (exact → fuzzy → any-language → .vtt) → TRANSLATE
 *     d) Otherwise → pending (with guidance, not silence)
 *  3. Translates with chunked batches + persistent translation memory
 *     (≈10× fewer model calls than v1.0, no lag between runs)
 *  4. Writes timing-safe `*.SubArabify.ar.srt` (dialogue timecodes bit-identical)
 *  5. Saves an in-app preview (first cues EN/AR) so the app shows subtitles
 *     like a normal player instead of just a file name.
 */
class SubArabifyWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SubArabifyWorker"
        private const val PREFS = "subarabify_prefs"
        /** Reject promo / stub SRTs (YTS ads are often < ~20 cues). */
        const val MIN_CUES = 30
        const val MIN_BYTES = 2_000
        const val PREVIEW_CUES = 8
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUriString = inputData.getString("LIBRARY_FOLDER_URI")
            ?: return@withContext Result.failure()
        val folderUri = Uri.parse(folderUriString)
        val directory = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext Result.failure()

        val forceRetranslate = inputData.getBoolean("FORCE_RETRANSLATE", false)
        val targetBase = inputData.getString("TARGET_BASE") // null = all
        val oneOnly = inputData.getBoolean("ONE_ONLY", false)

        val memory = TranslationMemory(context.applicationContext)
        val translator = MlKitTranslator(memory)
        if (!translator.prepareModel()) {
            Log.w(TAG, "ML Kit model not ready, will retry")
            return@withContext Result.retry()
        }
        markModelReady(true)

        val skipSet = loadSkipSet()
        val stats = ScanStats()

        scanAndProcessFolder(
            directory, translator, skipSet, stats,
            forceRetranslate, targetBase, oneOnly,
        )
        translator.close()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevDone = prefs.getInt("files_processed", 0)
        prefs.edit()
            .putInt("files_processed", prevDone + stats.translated)
            .putString("last_scan", System.currentTimeMillis().toString())
            .putInt("last_cached_lines", memory.size())
            .apply()

        stats.logEntries.forEach { appendLogEntry(it) }

        Log.i(
            TAG,
            "Scan complete: ${stats.translated} translated, ${stats.skipped} skipped, " +
                "${stats.alreadyDone} already done, ${stats.pending} pending, " +
                "${stats.weakSource} weak source, mem=${stats.fromMemory}, " +
                "glossary=${stats.fromGlossary}, model=${stats.fromModel}"
        )
        Result.success()
    }

    private data class ScanStats(
        var translated: Int = 0,
        var alreadyDone: Int = 0,
        var skipped: Int = 0,
        var pending: Int = 0,
        var weakSource: Int = 0,
        var fromMemory: Int = 0,
        var fromGlossary: Int = 0,
        var fromModel: Int = 0,
        val logEntries: MutableList<String> = mutableListOf(),
    )

    private suspend fun scanAndProcessFolder(
        folder: DocumentFile,
        translator: MlKitTranslator,
        skipSet: Set<String>,
        stats: ScanStats,
        forceRetranslate: Boolean,
        targetBase: String?,
        oneOnly: Boolean,
    ): Boolean {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                val stop = scanAndProcessFolder(
                    file, translator, skipSet, stats,
                    forceRetranslate, targetBase, oneOnly,
                )
                if (stop) return true
                continue
            }

            val fileName = file.name ?: continue
            if (!StorageHelper.isVideoFile(fileName)) continue

            val baseName = fileName.substringBeforeLast(".")

            if (targetBase != null && !baseName.equals(targetBase, ignoreCase = true)) {
                continue
            }

            // ── Already have Arabic output? ──
            val existingAr = findExistingArabicSrt(folder, baseName)
            if (existingAr != null && !forceRetranslate) {
                stats.alreadyDone++
                markFolderStatus(baseName, "done")
                continue
            }

            if (skipSet.contains(baseName) || skipSet.contains(fileName)) {
                stats.skipped++
                markFolderStatus(baseName, "skipped")
                continue
            }

            val srcFile = StorageHelper.findBestSource(folder, baseName)
            if (srcFile == null) {
                stats.pending++
                markFolderStatus(baseName, "pending")
                savePreview(baseName, emptyList(), 0, noSource = true)
                stats.logEntries.add("$baseName|||pending|||${System.currentTimeMillis()}")
                if (oneOnly && targetBase != null) return true
                continue
            }

            // Peek size / cue count — skip YTS promo stubs
            val peek = readText(srcFile) ?: continue
            val blocks = try { SrtParser.parse(peek) } catch (_: Exception) { emptyList() }
            if (blocks.isEmpty()) {
                stats.pending++
                markFolderStatus(baseName, "pending")
                stats.logEntries.add("$baseName|||pending|||${System.currentTimeMillis()}")
                if (oneOnly && targetBase != null) return true
                continue
            }
            if (blocks.size < MIN_CUES || (srcFile.length() in 1 until MIN_BYTES.toLong())) {
                Log.w(TAG, "Weak English SRT for $baseName (${blocks.size} cues) — need fuller .en.srt")
                stats.weakSource++
                markFolderStatus(baseName, "weak_source")
                stats.logEntries.add("$baseName|||weak_source|||${System.currentTimeMillis()}")
                savePreview(baseName, SrtParser.buildPreviews(blocks, blocks.map { it.textLines }, 3), blocks.size, weak = true)
                if (oneOnly && targetBase != null) return true
                continue
            }

            markFolderStatus(baseName, "translating")
            setProgressText(baseName, 0, blocks.size)
            if (forceRetranslate || existingAr != null) {
                deleteArabicOutputs(folder, baseName)
            }

            val success = translateExistingSrt(
                folder, srcFile, baseName, blocks, translator,
                onProgress = { done, total -> setProgressText(baseName, done, total) },
            )
            if (success) {
                stats.translated++
                stats.fromMemory += translator.lastStats.fromMemory
                stats.fromGlossary += translator.lastStats.fromGlossary
                stats.fromModel += translator.lastStats.fromModel
                markFolderStatus(baseName, "done")
                stats.logEntries.add("$baseName|||success|||${System.currentTimeMillis()}")
            } else {
                stats.pending++
                markFolderStatus(baseName, "error")
                stats.logEntries.add("$baseName|||error|||${System.currentTimeMillis()}")
            }

            if (oneOnly) return true
        }
        return false
    }

    /** Detect player-safe and legacy/mangled Arabic outputs. */
    private fun findExistingArabicSrt(folder: DocumentFile, baseName: String): DocumentFile? {
        val names = listOf(
            "$baseName.SubArabify.ar.srt",
            "${baseName}_SubArabify_ar.srt",
            "$baseName.SubArabify.ar", // some providers add .srt later
            "$baseName.ar.srt",
        )
        for (name in names) {
            folder.findFile(name)?.let { return it }
        }
        return folder.listFiles().firstOrNull { f ->
            val n = f.name ?: return@firstOrNull false
            n.startsWith(baseName) && (
                n.contains("SubArabify", ignoreCase = true) && n.endsWith(".srt", true)
                )
        }
    }

    private fun deleteArabicOutputs(folder: DocumentFile, baseName: String) {
        folder.listFiles().forEach { f ->
            val n = f.name ?: return@forEach
            if (!n.endsWith(".srt", true)) return@forEach
            if (!n.startsWith(baseName)) return@forEach
            val isOurs = n.contains("SubArabify", ignoreCase = true) ||
                n.equals("$baseName.ar.srt", ignoreCase = true)
            if (isOurs) f.delete()
        }
    }

    private fun readText(file: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Read failed: ${file.name}", e)
            null
        }
    }

    private suspend fun translateExistingSrt(
        parentFolder: DocumentFile,
        srcFile: DocumentFile,
        baseName: String,
        blocks: List<com.subarabify.data.SrtBlock>,
        translator: MlKitTranslator,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Boolean {
        return try {
            // Chunked + cached: ~10 model calls per movie, not ~1000
            val translatedBlocks = translator.translateBlocks(blocks) { done, total ->
                onProgress?.invoke(done, total)
            }

            val arSrtContent = SrtParser.buildBrandedSrt(blocks, translatedBlocks)

            // SAF often turns "a.b.c.srt" into "a_b_c.srt". Pass name WITHOUT
            // a trailing .srt and let the MIME add .srt → "base.SubArabify.ar.srt"
            val createName = "$baseName.SubArabify.ar"
            val newFile = parentFolder.createFile("application/x-subrip", createName)
                ?: parentFolder.createFile("text/plain", createName)
                ?: return false

            context.contentResolver.openOutputStream(newFile.uri)?.use {
                it.write(arSrtContent.toByteArray(Charsets.UTF_8))
            } ?: return false

            // Save preview so the app shows real subtitles, not just a filename
            val previews = SrtParser.buildPreviews(blocks, translatedBlocks, PREVIEW_CUES)
            savePreview(baseName, previews, blocks.size)

            Log.i(TAG, "Wrote Arabic SRT as '${newFile.name}' (${blocks.size} cues) from '${srcFile.name}' " +
                "(mem=${translator.lastStats.fromMemory}, glo=${translator.lastStats.fromGlossary}, model=${translator.lastStats.fromModel})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed for $baseName", e)
            false
        }
    }

    /** Persist a small preview JSON for the in-app subtitle viewer. */
    private fun savePreview(
        baseName: String,
        previews: List<com.subarabify.data.CuePreview>,
        totalCues: Int,
        noSource: Boolean = false,
        weak: Boolean = false,
    ) {
        try {
            val sp = context.getSharedPreferences("subarabify_preview", Context.MODE_PRIVATE)
            val arr = JSONArray()
            previews.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("tc", p.timecode)
                        .put("en", p.en)
                        .put("ar", p.ar)
                )
            }
            val obj = JSONObject()
                .put("total", totalCues)
                .put("cues", arr)
                .put("ts", System.currentTimeMillis())
                .put("noSource", noSource)
                .put("weak", weak)
            sp.edit().putString("preview_$baseName", obj.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun setProgressText(baseName: String, done: Int, total: Int) {
        try {
            val sp = context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
            sp.edit().putString("progress_$baseName", "$done/$total").apply()
        } catch (_: Exception) { }
    }

    private fun markFolderStatus(baseName: String, status: String) {
        val prefs = context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
        prefs.edit().putString("status_$baseName", status).apply()
    }

    private fun markModelReady(ready: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("model_ready", ready)
                .putString("model_name", "ML Kit EN→AR (on-device)")
                .apply()
        } catch (_: Exception) { }
    }

    private fun loadSkipSet(): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet("skip_list", emptySet()) ?: emptySet()
    }

    private fun appendLogEntry(entry: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("log_entries", "") ?: ""
        val updated = if (existing.isBlank()) entry else "$existing|||$entry"
        // keep log bounded (last ~300 entries)
        val parts = updated.split("|||")
        val trimmed = if (parts.size > 900) parts.takeLast(900).joinToString("|||") else updated
        prefs.edit().putString("log_entries", trimmed).apply()
    }
}
