package com.subarabify.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.subarabify.data.AudioExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the SubArabify Colab backend (backend/
 * SubArabify_Backend.ipynb, v0.2.2-beta).
 *
 * This class is the ONLY engine the app talks to. Two job kinds:
 *   * translate — multipart field `srt=` (English subtitle → Arabic, timings
 *     kept bit-identical)
 *   * transcribe — multipart field `file=` (16 kHz mono WAV extracted from the
 *     video's audio track with [AudioExtractor] → direct Arabic transcript)
 *
 * Jobs are asynchronous (a 2 h movie takes ~15–20 min on the T4): submit →
 * poll GET /jobs/{id} → GET /jobs/{id}/srt when done. No ML Kit, no Vosk.
 */
class BackendClient(baseUrl: String) {

    class BackendException(message: String) : Exception(message)

    data class Health(
        val ok: Boolean,
        val modelLoaded: Boolean,
        val mtLoaded: Boolean,
        val version: String?,
        val device: String?,
        val error: String? = null,
    )

    data class JobStatus(
        val jobId: String,
        val kind: String,
        val status: String,
        val progress: Float,
        val message: String,
    )

    private val base = baseUrl.trim().trimEnd('/')
    private val tag = "BackendClient"

    init {
        require(base.startsWith("http")) { "backend URL must start with http(s)://" }
    }

    /** GET /health — never throws; returns ok=false with a reason on failure. */
    suspend fun health(): Health = withContext(Dispatchers.IO) {
        try {
            val conn = open("GET", "$base/health")
            val code = conn.responseCode
            val body = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
            conn.disconnect()
            if (code !in 200..299) {
                Health(false, false, false, null, null, "HTTP $code: ${body.take(160)}")
            } else {
                val j = JSONObject(body)
                Health(
                    ok = j.optString("status") == "ok",
                    modelLoaded = j.optBoolean("model_loaded", false),
                    mtLoaded = j.optBoolean("mt_loaded", false),
                    version = j.optString("version", null),
                    device = j.optString("device", null),
                )
            }
        } catch (e: Exception) {
            Health(false, false, false, null, null, e.message)
        }
    }

    /** Upload a subtitle file as the `srt=` field → translate job. */
    suspend fun submitTranslate(srt: DocumentFile, context: Context): String =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(srt.uri)?.use { it.readBytes() }
                ?: throw IOException("cannot read ${srt.name}")
            val body = multipartBody(bytes, "srt", srt.name ?: "subs.srt", "text/plain")
            postMultipart("$base/jobs", body)
        }

    /**
     * Extract the video's audio track to a 16 kHz mono WAV ([AudioExtractor],
     * no FFmpeg on Android), upload it as the `file=` field → transcribe job,
     * then delete the WAV. Returns the job id.
     */
    suspend fun submitTranscribe(
        context: Context,
        videoUri: Uri,
        cacheDir: File,
        jobName: String,
        onPhase: (pct: Int, note: String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val jobsDir = File(cacheDir, "subarabify_jobs").apply { mkdirs() }
        val wav = File(jobsDir, sanitizeBase(jobName) + ".wav")
        try {
            onPhase(1, "extracting audio…")
            writeWav(context, videoUri, wav, onPhase)
            onPhase(95, "uploading…")
            uploadFile(wav)
        } finally {
            runCatching { wav.delete() }   // a movie WAV can be ~1 GB
        }
    }

    private fun writeWav(
        context: Context,
        videoUri: Uri,
        file: File,
        onPhase: (pct: Int, note: String) -> Unit,
    ) {
        val pcmBytes = java.util.concurrent.atomic.AtomicLong(0)
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            out.write(ByteArray(44)) // RIFF header placeholder
            val info = AudioExtractor.decode(
                context,
                videoUri,
                onPcm = { shorts, len ->
                    val b = ByteArray(len * 2)
                    for (i in 0 until len) {
                        val s = shorts[i].toInt()
                        b[i * 2] = (s and 0xFF).toByte()
                        b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(b)
                    pcmBytes.addAndGet(b.size.toLong())
                },
                onProgress = { decodedUs, totalUs ->
                    val pct = if (totalUs > 0) (decodedUs * 100 / totalUs).toInt().coerceIn(0, 90)
                    else 0
                    onPhase(pct, "extracting audio…")
                },
            )
            if (info == null) {
                throw BackendException("no decodable audio track in this video")
            }
            out.flush()
        }
        val dataLen = pcmBytes.get()
        RandomAccessFile(file, "rw").use { raf ->
            val byteRate = 16_000 * 2L                 // 16 kHz * mono * 16-bit
            raf.seek(0)
            // java.io.RandomAccessFile writes big-endian (matches RIFF spec)
            raf.write("RIFF".toByteArray())
            raf.writeInt(36 + dataLen.toInt())         // RIFF chunk size
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.writeInt(16)                           // fmt chunk size
            raf.writeShort(1)                          // PCM
            raf.writeShort(1)                          // mono
            raf.writeInt(16_000)                       // sample rate
            raf.writeInt(byteRate.toInt())             // byte rate
            raf.writeShort(2)                          // block align
            raf.writeShort(16)                         // bits per sample
            raf.write("data".toByteArray())
            raf.writeInt(dataLen.toInt())
        }
    }

    /** Multipart upload of a whole file (used for the extracted WAV). */
    private suspend fun uploadFile(file: File): String = withContext(Dispatchers.IO) {
        try {
            file.inputStream().use { ins ->
                val conn = open("POST", "$base/jobs")
                conn.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$BOUNDARY",
                )
                val header = partHeader("file", file.name, "audio/wav")
                val footer = partFooter()
                conn.doOutput = true
                conn.setFixedLengthStreamingMode((header.length + file.length() + footer.length).toInt())
                conn.outputStream.use { out ->
                    out.write(header.toByteArray())
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.write(footer.toByteArray())
                    out.flush()
                }
                val code = conn.responseCode
                val resp = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
                conn.disconnect()
                parseJobId(code, resp)
            }
        } catch (e: IOException) {
            throw BackendException("upload failed: ${e.message}")
        }
    }

    /**
     * Poll POSTed /jobs/{id} until done/error. Returns the finished plain SRT
     * text (branding happens in the worker). Tolerates transient tunnel
     * failures; enforces an overall deadline and gives up cleanly.
     */
    suspend fun waitFor(
        jobId: String,
        jobKind: String,
        timeoutMs: Long = 6 * 60 * 60 * 1000L,
        onPhase: (pct: Int, note: String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastPct = -1
        var misses = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                val status = fetchStatus(jobId)
                misses = 0
                when (status.status) {
                    "done" -> return@withContext downloadSrt(jobId)
                    "error" -> throw BackendException(
                        "backend error: ${status.message.ifBlank { "unknown" }}",
                    )
                    else -> {
                        val pct = (status.progress * 100).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onPhase(pct, status.message.ifBlank { status.status })
                        }
                    }
                }
            } catch (e: BackendException) {
                throw e
            } catch (e: Exception) {
                misses++
                if (misses >= MAX_POLL_MISSES) {
                    throw BackendException(
                        "repeated tunnel failures while waiting ($jobId): ${e.message}",
                    )
                }
                Log.w(tag, "poll blip ($misses): $e")
            }
            Thread.sleep(if (jobKind == "transcribe") 15_000L else 5_000L)
        }
        throw BackendException(
            "timed out waiting for $jobId — the Colab session may have slept; " +
                "a later scan can resume (the job may still finish server-side).",
        )
    }

    private fun fetchStatus(jobId: String): JobStatus {
        val conn = open("GET", "$base/jobs/$jobId")
        val code = conn.responseCode
        val body = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) throw BackendException("status fetch HTTP $code: ${body.take(200)}")
        val j = JSONObject(body)
        return JobStatus(
            jobId = jobId,
            kind = j.optString("kind", "transcribe"),
            status = j.optString("status"),
            progress = j.optDouble("progress", 0.0).toFloat(),
            message = j.optString("message"),
        )
    }

    private fun downloadSrt(jobId: String): String {
        val conn = open("GET", "$base/jobs/$jobId/srt")
        val code = conn.responseCode
        val body = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) throw BackendException("srt fetch HTTP $code: ${body.take(200)}")
        if (!body.contains("-->")) throw BackendException("backend response is not SRT")
        return body
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private fun open(method: String, urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("User-Agent", "SubArabify-Android/0.2.2-beta")
        conn.setRequestProperty("Accept", "application/json, text/plain")
        return conn
    }

    private fun postMultipart(urlStr: String, body: ByteArray): String {
        val conn = open("POST", urlStr)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val resp = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        return parseJobId(code, resp)
    }

    private fun parseJobId(code: Int, body: String): String {
        if (code !in 200..299) {
            throw BackendException("upload rejected (HTTP $code): ${body.take(240)}")
        }
        val j = JSONObject(body)
        return j.optString("job_id").ifEmpty {
            throw BackendException("backend answered without a job_id: ${body.take(200)}")
        }
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedInputStream(stream).reader(Charsets.UTF_8).use { it.readText() }
    }

    private fun multipartBody(bytes: ByteArray, field: String, filename: String, mime: String): ByteArray {
        val head = partHeader(field, filename, mime).toByteArray()
        val foot = partFooter().toByteArray()
        val out = ByteArray(head.size + bytes.size + foot.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(bytes, 0, out, head.size, bytes.size)
        System.arraycopy(foot, 0, out, head.size + bytes.size, foot.size)
        return out
    }

    private fun partHeader(field: String, filename: String, mime: String): String =
        "--$BOUNDARY\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
            "Content-Type: $mime\r\n\r\n"

    private fun partFooter(): String = "\r\n--$BOUNDARY--\r\n"

    private fun sanitizeBase(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)

    companion object {
        private const val BOUNDARY = "SubArabify-0.2.2-beta"
        private const val MAX_POLL_MISSES = 12
    }
}