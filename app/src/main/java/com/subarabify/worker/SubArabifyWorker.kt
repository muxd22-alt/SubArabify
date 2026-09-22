package com.subarabify.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.subarabify.data.CuePreview
import com.subarabify.data.SrtParser
import com.subarabify.data.StorageHelper
import com.subarabify.engine.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background worker (0.2.2-beta) — the app's ONLY engine is the Colab T4
 * backend (backend/SubArabify_Backend.ipynb). Everything heavy happens there;
 * this worker scans, routes and brands:
 *
 *  1. Scan the user-selected media folder recursively
 *  2. For each video:
 *     a) `*.SubArabify.ar.srt` exists  → DONE (unless FORCE_RETRANSLATE)
 *     b) skip-listed                    → SKIPPED
 *     c) English subtitle found         → upload as `srt=` → backend TRANSLATES
 *        to Arabic (timings kept bit-identical)
 *     d) no subtitle at all             → extract the audio track to a 16 kHz
 *        WAV (AudioExtractor, no FFmpeg) → upload as `file=` → backend
 *        TRANSCRIBES the soundtrack to Arabic directly
 *  3. Brand the returned plain SRT (— SubArabify —/NOTE safe-gap cues) via
 *     SrtParser — identical rules to the Python client's srtcore.
 *  4. Write `*.SubArabify.ar.srt` and save an in-app preview.
 */
class SubArabifyWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SubArabifyWorker"
        private const val PREFS = "subarabify_prefs"
        /** Reject promo / stub SRTs (YTS ads are often < ~20 cues). */
        const val MIN_CUES = 30
        const val MIN_BYTES = 2_000
        const val PREVIEW_CUES = 8
        const val PROGRESS_BASE = "progress_base"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUriString = inputData.getString("LIBRARY_FOLDER_URI")
            ?: return@withContext Result.failure()
        val folderUri = Uri.parse(folderUriString)
        val directory = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext Result.failure()

        val backendUrl = (inputData.getString("BACKEND_URL")
            ?: prefs().getString("backend_url", "") ?: "").trim().trimEnd('/')
        if (backendUrl.isBlank()) {
            appendLogEntry("backend|||backend_offline (no URL configured)|||${System.currentTimeMillis()}")
            Log.w(TAG, "no backend URL configured — set it in the app first")
            return@withContext Result.failure()
        }

        val client = BackendClient(backendUrl)
        val health = client.health()
        if (!health.ok) {
            val why = health.error?.let { " ($it)" } ?: ""
            appendLogEntry("backend|||backend_offline$why|||${System.currentTimeMillis()}")
            Log.w(TAG, "backend unreachable: ${health.error}")
            // non-fatal — retry with backoff instead of aborting permanently
            return@withContext Result.retry()
        }
        markModelReady(true, health)

        val forceRetranslate = inputData.getBoolean("FORCE_RETRANSLATE", false)
        val targetBase = inputData.getString("TARGET_BASE") // null = all
        val oneOnly = inputData.getBoolean("ONE_ONLY", false)

        val skipSet = loadSkipSet()
        val stats = ScanStats()

        scanAndProcessFolder(
            directory, client, skipSet, stats, forceRetranslate, targetBase, oneOnly,
        )

        val prevDone = prefs().getInt("files_processed", 0)
        prefs().edit()
            .putInt("files_processed", prevDone + stats.processed)
            .putString("last_scan", System.currentTimeMillis().toString())
            .apply()

        stats.logEntries.forEach { appendLogEntry(it) }

        Log.i(
            TAG,
            "Scan complete: ${stats.processed} processed " +
                "(${stats.translated} translated, ${stats.transcribed} transcribed, " +
                "${stats.alreadyDone} already done, ${stats.skipped} skipped, " +
                "${stats.weakSource} weak source, ${stats.pending} pending)",
        )
        Result.success()
    }

    private data class ScanStats(
        var processed: Int = 0,
        var translated: Int = 0,
        var transcribed: Int = 0,
        var alreadyDone: Int = 0,
        var skipped: Int = 0,
        var pending: Int = 0,
        var weakSource: Int = 0,
        val logEntries: MutableList<String> = mutableListOf(),
    )

    private suspend fun scanAndProcessFolder(
        folder: DocumentFile,
        client: BackendClient,
        skipSet: Set<String>,
        stats: ScanStats,
        forceRetranslate: Boolean,
        targetBase: String?,
        oneOnly: Boolean,
    ): Boolean {
        for (file in folder.listFiles()) {
            if (file.isDirectory) {
                val stop = scanAndProcessFolder(
                    file, client, skipSet, stats, forceRetranslate, targetBase, oneOnly,
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

            // ── Already have Arabic output? skip ──
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
            val ok = if (srcFile != null) {
                routeTranslate(folder, srcFile, baseName, client, stats, forceRetranslate)
            } else {
                routeTranscribe(folder, file, baseName, client, stats, forceRetranslate)
            }

            if (ok) stats.processed++
            if (oneOnly) return true
        }
        return false
    }

    // ── route 1: English subtitle → backend SRT translate ─────────────────

    private suspend fun routeTranslate(
        parentFolder: DocumentFile,
        srcFile: DocumentFile,
        baseName: String,
        client: BackendClient,
        stats: ScanStats,
        forceRetranslate: Boolean,
    ): Boolean {
        // Peek size / cue count — skip YTS promo stubs
        val blocks = readText(srcFile)?.let {
            try { SrtParser.parse(it) } catch (_: Exception) { emptyList() }
        } ?: emptyList()
        if (blocks.isEmpty()) {
            markFolderStatus(baseName, "pending")
            savePreview(baseName, emptyList(), 0, noSource = true)
            stats.pending++
            stats.logEntries.add("$baseName|||pending|||${System.currentTimeMillis()}")
            return false
        }
        if (blocks.size < MIN_CUES || (srcFile.length() in 1 until MIN_BYTES.toLong())) {
            Log.w(
                TAG,
                "Weak English SRT for $baseName (${blocks.size} cues) — need fuller .en.srt",
            )
            markFolderStatus(baseName, "weak_source")
            savePreview(
                baseName,
                SrtParser.buildPreviews(blocks, blocks.map { it.textLines }, 3),
                blocks.size,
                weak = true,
            )
            stats.weakSource++
            stats.logEntries.add("$baseName|||weak_source|||${System.currentTimeMillis()}")
            return false
        }

        markFolderStatus(baseName, "translating")
        if (forceRetranslate) deleteArabicOutputs(parentFolder, baseName)

        return try {
            val jobId = client.submitTranslate(srcFile, context)
            val srt = client.waitFor(jobId, "translate") { pct, _ ->
                setProgressText(baseName, pct, 100)
            }
            val written = brandAndWrite(parentFolder, baseName, srt)
            if (written) {
                stats.translated++
                markFolderStatus(baseName, "done")
                stats.logEntries.add("$baseName|||translate_success|||${System.currentTimeMillis()}")
                Log.i(TAG, "Translated on the backend: '${srcFile.name}' → $baseName.SubArabify.ar.srt")
            }
            written
        } catch (e: Exception) {
            markFolderStatus(baseName, "error")
            stats.logEntries.add("$baseName|||error: $e|||${System.currentTimeMillis()}")
            Log.e(TAG, "Backend translate failed for $baseName", e)
            false
        }
    }

    // ── route 2: no subtitle → extract audio → backend transcribe ─────────

    private suspend fun routeTranscribe(
        parentFolder: DocumentFile,
        videoFile: DocumentFile,
        baseName: String,
        client: BackendClient,
        stats: ScanStats,
        forceRetranslate: Boolean,
    ): Boolean {
        markFolderStatus(baseName, "transcribing")
        setProgressText(baseName, 0, 100)
        if (forceRetranslate) deleteArabicOutputs(parentFolder, baseName)

        return try {
            val jobId = client.submitTranscribe(
                context,
                videoFile.uri,
                context.cacheDir,
                baseName,
            ) { pct, _ -> setProgressText(baseName, pct, 100) }
            val srt = client.waitFor(jobId, "transcribe") { pct, _ ->
                setProgressText(baseName, pct, 100)
            }
            val written = brandAndWrite(parentFolder, baseName, srt)
            if (written) {
                stats.transcribed++
                markFolderStatus(baseName, "done")
                stats.logEntries.add("$baseName|||transcribe_success|||${System.currentTimeMillis()}")
                Log.i(TAG, "Transcribed on the backend → $baseName.SubArabify.ar.srt")
            }
            written
        } catch (e: Exception) {
            markFolderStatus(baseName, "error")
            setProgressText(baseName, 0, 0)
            stats.logEntries.add("$baseName|||error: $e|||${System.currentTimeMillis()}")
            Log.e(TAG, "Backend transcribe failed for $baseName", e)
            false
        }
    }

    /** Brand the backend plain SRT (shared rules) and write the output. */
    private fun brandAndWrite(
        parentFolder: DocumentFile,
        baseName: String,
        srtText: String,
    ): Boolean {
        val blocks = try {
            SrtParser.parse(srtText)
        } catch (_: Exception) { emptyList() }
        if (blocks.isEmpty()) {
            Log.w(TAG, "backend returned something unparseable for $baseName")
            return false
        }
        // The backend already returns ARABIC lines (translate or transcribe).
        val translated = blocks.map { it.textLines }
        val arContent = SrtParser.buildBrandedSrt(blocks, translated)

        // SAF often turns "a.b.c.srt" into "a_b_c.srt"; pass the name WITHOUT a
        // trailing .srt so the MIME provider adds it → "...SubArabify.ar.srt"
        val createName = "$baseName.SubArabify.ar"
        val newFile = parentFolder.createFile("application/x-subrip", createName)
            ?: parentFolder.createFile("text/plain", createName)
            ?: return false
        context.contentResolver.openOutputStream(newFile.uri)?.use {
            it.write(arContent.toByteArray(Charsets.UTF_8))
        } ?: return false

        savePreview(
            baseName,
            SrtParser.buildPreviews(blocks, translated, PREVIEW_CUES),
            blocks.size,
        )
        Log.i(TAG, "Wrote Arabic SRT as '${newFile.name}' (${blocks.size} cues)")
        return true
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
            n.startsWith(baseName) && n.contains("SubArabify", ignoreCase = true) &&
                n.endsWith(".srt", true)
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

    /** Persist a small preview JSON for the in-app subtitle viewer. */
    private fun savePreview(
        baseName: String,
        previews: List<CuePreview>,
        totalCues: Int,
        noSource: Boolean = false,
        weak: Boolean = false,
    ) {
        try {
            val sp = context.getSharedPreferences("subarabify_preview", Context.MODE_PRIVATE)
            val existing = try {
                JSONObject(sp.getString("preview_$baseName", "{}"))
            } catch (e: Exception) { JSONObject() }
            val arr = JSONArray()
            previews.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("tc", p.timecode)
                        .put("en", p.en)
                        .put("ar", p.ar),
                )
            }
            existing.put("total", totalCues)
            existing.put("noSource", noSource)
            existing.put("weak", weak)
            existing.put("cues", arr)
            sp.edit().putString("preview_$baseName", existing.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "SavePreview failed for $baseName", e)
        }
    }

    /** Folder-status prefs the UI reads from `subarabify_status` (status_<name>). */
    private fun markFolderStatus(baseName: String, status: String) {
        context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
            .edit()
            .putString("status_$baseName", status)
            .apply()
    }

    private fun loadSkipSet(): Set<String> {
        return prefs().getStringSet("skip_list", emptySet()) ?: emptySet()
    }

    private fun setProgressText(baseName: String, done: Int, total: Int) {
        // setProgressAsync (non-suspend) — may be called from BackendClient's
        // plain onPhase callbacks, which cannot call suspend functions.
        setProgressAsync(workDataOf(PROGRESS_BASE to baseName, PROGRESS_DONE to done, PROGRESS_TOTAL to total))
        // the UI reads live progress from subarabify_status: progress_<name>
        val shown = if (total > 0) "${(done.toLong() * 100 / total).toInt()}%" else "$done"
        context.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)
            .edit()
            .putString("progress_$baseName", shown)
            .apply()
    }

    private fun markModelReady(ready: Boolean, health: BackendClient.Health) {
        prefs().edit()
            .putBoolean("model_ready", ready)
            .putString("model_name", if (ready) "Colab T4 backend (0.2.2-beta)" else "not ready")
            .putString("mt_loaded", health.mtLoaded.toString())
            .putString("model_device", health.device ?: "")
            .apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The UI reads `log_entries` as a flat "|||" concatenation of
     * fileName|||status|||timestamp triples (see MainActivity.loadLog) —
     * oldest first, newest appended at the end.
     */
    private fun appendLogEntry(entry: String) {
        val log = prefs().getString("log_entries", "").orEmpty()
        val combined = if (log.isBlank()) entry else "$log$entry"
        val parts = combined.split("|||")
        val capped = if (parts.size > 600) parts.takeLast(600).joinToString("|||") else combined
        prefs().edit().putString("log_entries", capped).apply()
    }
}