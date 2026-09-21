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
 *  2. For each video (or a single TARGET_BASE):
 *     a) Checks if Arabic output already exists → DONE (unless FORCE_RETRANSLATE)
 *     b) Checks if in user skip list → SKIPPED
 *     c) Picks the best English .srt (not tiny YTS promos) → TRANSLATE
 *     d) Otherwise → pending (whisper stub)
 *  3. Writes a player-safe `*.SubArabify.ar.srt` (SAF-safe create name)
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

        val translator = MlKitTranslator()
        if (!translator.prepareModel()) {
            Log.w(TAG, "ML Kit model not ready, will retry")
            return@withContext Result.retry()
        }

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
            .apply()

        stats.logEntries.forEach { appendLogEntry(it) }

        Log.i(
            TAG,
            "Scan complete: ${stats.translated} translated, ${stats.skipped} skipped, " +
                "${stats.alreadyDone} already done, ${stats.pending} pending, ${stats.weakSource} weak source"
        )
        Result.success()
    }

    private data class ScanStats(
        var translated: Int = 0,
        var alreadyDone: Int = 0,
        var skipped: Int = 0,
        var pending: Int = 0,
        var weakSource: Int = 0,
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
            if (!isVideoFile(fileName)) continue

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

            val enSrtFile = findBestEnglishSrt(folder, baseName)
            if (enSrtFile == null) {
                stats.pending++
                markFolderStatus(baseName, "pending")
                stats.logEntries.add("$baseName|||pending|||${System.currentTimeMillis()}")
                if (oneOnly && targetBase != null) return true
                continue
            }

            // Peek size / cue count — skip YTS promo stubs
            val peek = readText(enSrtFile) ?: continue
            val blocks = SrtParser.parse(peek)
            if (blocks.size < MIN_CUES || (enSrtFile.length() in 1 until MIN_BYTES.toLong())) {
                Log.w(TAG, "Weak English SRT for $baseName (${blocks.size} cues) — need fuller .en.srt")
                stats.weakSource++
                markFolderStatus(baseName, "weak_source")
                stats.logEntries.add("$baseName|||weak_source|||${System.currentTimeMillis()}")
                if (oneOnly && targetBase != null) return true
                continue
            }

            markFolderStatus(baseName, "translating")
            // Remove old / mangled outputs before rewrite
            if (forceRetranslate || existingAr != null) {
                deleteArabicOutputs(folder, baseName)
            }

            val success = translateExistingSrt(folder, enSrtFile, baseName, blocks, translator)
            if (success) {
                stats.translated++
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

    /**
     * Prefer language-tagged English subs, then the largest real .srt.
     * Never prefer tiny sidecar promos over a proper .en.srt.
     */
    private fun findBestEnglishSrt(folder: DocumentFile, baseName: String): DocumentFile? {
        val tagged = listOf(
            "$baseName.en.srt",
            "$baseName.eng.srt",
            "$baseName.English.srt",
            "$baseName.en-US.srt",
            "$baseName.en-GB.srt",
        ).mapNotNull { folder.findFile(it) }

        val bare = folder.findFile("$baseName.srt")

        val candidates = (tagged + listOfNotNull(bare))
            .filter { f ->
                val n = f.name?.lowercase().orEmpty()
                !n.contains("subarabify") && !n.endsWith(".ar.srt")
            }

        if (candidates.isEmpty()) return null

        // Prefer tagged names; among equals, largest file wins
        return candidates.maxWithOrNull(
            compareBy<DocumentFile> { f ->
                val n = f.name?.lowercase().orEmpty()
                when {
                    n.contains(".en.") || n.endsWith(".en.srt") || n.contains(".eng.") ||
                        n.contains(".english.") -> 2
                    else -> 0
                }
            }.thenBy { it.length() }
        )
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
        // Fuzzy: any sibling ending with SubArabify_ar.srt / SubArabify.ar.srt
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
        enSrtFile: DocumentFile,
        baseName: String,
        blocks: List<com.subarabify.data.SrtBlock>,
        translator: MlKitTranslator,
    ): Boolean {
        return try {
            val translatedBlocks = mutableListOf<List<String>>()
            for (block in blocks) {
                translatedBlocks.add(translator.translateBatch(block.textLines))
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

            Log.i(TAG, "Wrote Arabic SRT as '${newFile.name}' (${blocks.size} cues) from '${enSrtFile.name}'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed for $baseName", e)
            false
        }
    }

    private fun markFolderStatus(baseName: String, status: String) {
        val prefs = context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
        prefs.edit().putString("status_$baseName", status).apply()
    }

    private fun loadSkipSet(): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet("skip_list", emptySet()) ?: emptySet()
    }

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
