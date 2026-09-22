package com.subarabify.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.subarabify.data.AudioExtractor
import com.subarabify.data.SrtBlock
import com.subarabify.data.SrtParser
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * On-device English speech-to-text (1.0.4-pre, STT fallback).
 *
 * When a video has NO usable subtitle file, this engine transcribes the
 * audio track offline with Vosk (small-en, ~40 MB, downloaded once) and
 * returns timestamped English blocks. The worker then runs the normal
 * smart EN→AR translation on those blocks — same branding, same file.
 *
 * Audio is decoded + streamed (never a whole movie in RAM), word
 * timestamps come straight from the recognizer (real timings).
 */
class SttEngine(private val context: Context) {

    companion object {
        private const val TAG = "SttEngine"
        private const val PREFS = "subarabify_prefs"
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_DIR_NAME = "vosk-small-en"
        private const val MAX_CUE_CHARS = 42
        private const val MAX_CUE_MS = 6000L
        private const val GAP_SPLIT_MS = 800L
    }

    data class Word(val startMs: Long, val endMs: Long, val text: String)

    fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(): Boolean {
        val d = modelDir()
        // a complete vosk model dir has am/final.mdl + graph
        return d.isDirectory && File(d, "am/final.mdl").exists()
    }

    /** Downloads + unzips the small English model once. Returns false if offline/fails. */
    fun ensureModel(onProgress: ((done: Long, total: Long) -> Unit)? = null): Boolean {
        if (isModelReady()) {
            markSttReady(true)
            return true
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                connect()
            }
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var done = 0L
            val tmp = modelDir()
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            conn.inputStream.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    val scratch = ByteArray(32768)
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            // strip the top-level folder inside the zip
                            val rel = entry.name.substringAfter("/", entry.name)
                            if (rel.isNotEmpty() && rel != entry.name) {
                                val out = File(tmp, rel)
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos ->
                                    while (true) {
                                        val n = zip.read(scratch)
                                        if (n < 0) break
                                        fos.write(scratch, 0, n)
                                        done += n
                                        if (total > 0) onProgress?.invoke(done, total)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            // sanity: some zips nest one level deeper
            if (!File(tmp, "am/final.mdl").exists()) {
                tmp.walkTopDown().firstOrNull { it.name == "final.mdl" }?.parentFile?.parentFile?.let { nested ->
                    if (nested != tmp && nested.isDirectory) {
                        nested.copyRecursively(File(context.filesDir, "${MODEL_DIR_NAME}_new"), overwrite = true)
                        tmp.deleteRecursively()
                        File(context.filesDir, "${MODEL_DIR_NAME}_new").renameTo(tmp)
                    }
                }
            }
            val ok = isModelReady()
            markSttReady(ok)
            Log.i(TAG, "STT model ready=$ok")
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "STT model download failed", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Transcribe [videoUri] to English blocks with real timestamps.
     * Long movies stream through; progress is decodeWords-based.
     */
    fun transcribe(
        videoUri: Uri,
        onProgress: ((decodedUs: Long, totalUs: Long) -> Unit)? = null,
    ): List<SrtBlock> {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            // org.vosk.Model needs an absolute path string + creates native handle
            model = Model(modelDir().absolutePath)
            recognizer = Recognizer(model, AudioExtractor.TARGET_RATE.toFloat())
            recognizer.setWords(true)

            val words = mutableListOf<Word>()
            val rec = recognizer
            AudioExtractor.decode(context, videoUri, onPcm = { shorts, len ->
                if (rec.acceptWaveForm(shorts, len)) {
                    words += parseWords(rec.result)
                } else {
                    // harvest partial word timings cheaply? skip — finals carry timings
                }
                Unit
            }, onProgress = onProgress)
            words += parseWords(recognizer.finalResult)

            return groupWords(words)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return emptyList()
        } finally {
            try { (recognizer as? Closeable)?.close() } catch (_: Exception) { }
            try { model?.close() } catch (_: Exception) { }
        }
    }

    private fun parseWords(json: String): List<Word> {
        val out = mutableListOf<Word>()
        try {
            val arr = JSONObject(json).optJSONArray("result") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("word").trim()
                if (w.isEmpty()) continue
                out.add(
                    Word(
                        startMs = (o.optDouble("start", 0.0) * 1000).toLong(),
                        endMs = (o.optDouble("end", 0.0) * 1000).toLong(),
                        text = w,
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    /** Group words into subtitle cues: ≤42 chars, ≤6 s, split on ≥0.8 s gaps. */
    fun groupWords(words: List<Word>): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        var cur = mutableListOf<Word>()
        var curLen = 0
        fun flush() {
            if (cur.isEmpty()) return
            val start = cur.first().startMs
            val end = (cur.last().endMs).coerceAtLeast(start + 800)
            blocks.add(
                SrtBlock(
                    index = (blocks.size + 1).toString(),
                    timecode = "${SrtParser.msToTimecode(start)} --> ${SrtParser.msToTimecode(end)}",
                    textLines = listOf(cur.joinToString(" ") { it.text }),
                )
            )
            cur = mutableListOf()
            curLen = 0
        }
        for ((i, w) in words.withIndex()) {
            if (cur.isNotEmpty()) {
                val gap = w.startMs - cur.last().endMs
                val dur = w.endMs - cur.first().startMs
                if (gap >= GAP_SPLIT_MS || dur >= MAX_CUE_MS || curLen + w.text.length + 1 > MAX_CUE_CHARS) {
                    flush()
                }
            }
            // drop empty/confidence-less junk already filtered; keep word
            cur.add(w)
            curLen += w.text.length + 1
            if (i == words.size - 1) flush()
        }
        return blocks
    }

    private fun markSttReady(ready: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("stt_ready", ready)
                .putString("stt_model", "Vosk small-en (on-device)")
                .apply()
        } catch (_: Exception) { }
    }
}
