package com.subarabify.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.subarabify.data.SrtParser
import com.subarabify.engine.MlKitTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that:
 *  1. Scans the user-selected media folder recursively
 *  2. For each video:
 *     a) Checks if a .SubArabify.ar.srt already exists → DONE → skip
 *     b) Checks if in user skip list → SKIPPED → skip
 *     c) Checks if an English .srt exists → TRANSLATE it
 *     d) Otherwise → placeholder for on-device whisper transcription
 *  3. Updates folder status (done / pending) in SharedPreferences
 */
class SubArabifyWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SubArabifyWorker"
        private const val PREFS = "subarabify_prefs"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUriString = inputData.getString("LIBRARY_FOLDER_URI")
            ?: return@withContext Result.failure()
        val folderUri = Uri.parse(folderUriString)
        val directory = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext Result.failure()

        val translator = MlKitTranslator()
        if (!translator.prepareModel()) {
            Log.w(TAG, "ML Kit model not ready, will retry")
            return@withContext Result.retry()
        }

        val skipSet = loadSkipSet()
        val stats = ScanStats()

        scanAndProcessFolder(directory, translator, skipSet, stats)
        translator.close()

        // Persist scan stats
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevDone = prefs.getInt("files_processed", 0)
        prefs.edit()
            .putInt("files_processed", prevDone + stats.translated)
            .putString("last_scan", System.currentTimeMillis().toString())
            .apply()

        // Append to activity log
        stats.logEntries.forEach { appendLogEntry(it) }

        Log.i(TAG, "Scan complete: ${stats.translated} translated, ${stats.skipped} skipped, ${stats.alreadyDone} already done, ${stats.pending} pending")
        Result.success()
    }

    private data class ScanStats(
        var translated: Int = 0,
        var alreadyDone: Int = 0,
        var skipped: Int = 0,
        var pending: Int = 0,
        val logEntries: MutableList<String> = mutableListOf(),
    )

    // ── Recursive scanner ───────────────────────────────────────────
    private suspend fun scanAndProcessFolder(
        folder: DocumentFile,
        translator: MlKitTranslator,
        skipSet: Set<String>,
        stats: ScanStats,
    ) {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                scanAndProcessFolder(file, translator, skipSet, stats)
                continue
            }

            val fileName = file.name ?: continue
            if (!isVideoFile(fileName)) continue

            val baseName = fileName.substringBeforeLast(".")
            val brandedSrtName = "$baseName.SubArabify.ar.srt"

            // ── Already done? ──
            if (folder.findFile(brandedSrtName) != null) {
                stats.alreadyDone++
                markFolderStatus(folder.uri.toString(), baseName, "done")
                continue
            }

            // ── User skipped? ──
            if (skipSet.contains(baseName) || skipSet.contains(fileName)) {
                stats.skipped++
                markFolderStatus(folder.uri.toString(), baseName, "skipped")
                continue
            }

            // ── English SRT exists? → Translate ──
            val enSrtFile = findEnglishSrt(folder, baseName)
            if (enSrtFile != null) {
                markFolderStatus(folder.uri.toString(), baseName, "translating")
                val success = translateExistingSrt(folder, enSrtFile, brandedSrtName, translator)
                if (success) {
                    stats.translated++
                    markFolderStatus(folder.uri.toString(), baseName, "done")
                    stats.logEntries.add("$baseName|||success|||${System.currentTimeMillis()}")
                } else {
                    stats.pending++
                    markFolderStatus(folder.uri.toString(), baseName, "error")
                    stats.logEntries.add("$baseName|||error|||${System.currentTimeMillis()}")
                }
                continue
            }

            // ── No SRT → on-device transcription (pending for whisper engine) ──
            stats.pending++
            markFolderStatus(folder.uri.toString(), baseName, "pending")
            stats.logEntries.add("$baseName|||pending|||${System.currentTimeMillis()}")
        }
    }

    // ── Find English .srt with flexible naming ──────────────────────
    private fun findEnglishSrt(folder: DocumentFile, baseName: String): DocumentFile? {
        // Try common subtitle naming patterns
        val candidates = listOf(
            "$baseName.srt",
            "$baseName.en.srt",
            "$baseName.eng.srt",
            "$baseName.English.srt",
        )
        for (name in candidates) {
            val file = folder.findFile(name)
            if (file != null) return file
        }
        return null
    }

    // ── Translate and write branded SRT ─────────────────────────────
    private suspend fun translateExistingSrt(
        parentFolder: DocumentFile,
        enSrtFile: DocumentFile,
        brandedSrtName: String,
        translator: MlKitTranslator,
    ): Boolean {
        return try {
            val content = context.contentResolver.openInputStream(enSrtFile.uri)
                ?.bufferedReader()?.use { it.readText() } ?: return false

            val blocks = SrtParser.parse(content)
            val translatedBlocks = mutableListOf<List<String>>()

            for (block in blocks) {
                val translatedLines = translator.translateBatch(block.textLines)
                translatedBlocks.add(translatedLines)
            }

            // Start + end brand, free middle, filename carries SubArabify
            val arSrtContent = SrtParser.buildBrandedSrt(blocks, translatedBlocks)

            val newFile = parentFolder.createFile("application/x-subrip", brandedSrtName)
                ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use {
                it.write(arSrtContent.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed for $brandedSrtName", e)
            false
        }
    }

    // ── Status tracking per file ────────────────────────────────────
    private fun markFolderStatus(folderUri: String, baseName: String, status: String) {
        val prefs = context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
        prefs.edit().putString("status_$baseName", status).apply()
    }

    // ── Skip list ───────────────────────────────────────────────────
    private fun loadSkipSet(): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("skip_list", emptySet()) ?: emptySet()
        return raw
    }

    // ── Activity log persistence ────────────────────────────────────
    private fun appendLogEntry(entry: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("log_entries", "") ?: ""
        val updated = if (existing.isBlank()) entry else "$existing|||$entry"
        prefs.edit().putString("log_entries", updated).apply()
    }

    private fun isVideoFile(fileName: String): Boolean {
        val extensions = listOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv", "webm")
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }
}
