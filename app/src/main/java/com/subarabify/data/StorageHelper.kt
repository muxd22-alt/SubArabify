package com.subarabify.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object StorageHelper {
    // Boilerplate helper for Storage Access Framework actions
    fun getFolderFromUri(context: Context, folderUriString: String): DocumentFile? {
        val folderUri = Uri.parse(folderUriString)
        return DocumentFile.fromTreeUri(context, folderUri)
    }

    private val VIDEO_EXTS = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv", "webm", "ts", "m2ts")
    private val SUB_EXTS = setOf("srt", "vtt")

    fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return ext in VIDEO_EXTS
    }

    fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return ext in SUB_EXTS
    }

    /**
     * Smart base-name: strip extension, then strip trailing language tags
     * (.en / .eng / .english / .ar / .arabic), SubArabify brand, resolution
     * tags are KEPT (they belong to the video), case-insensitive.
     */
    fun smartBase(fileName: String): String {
        var base = fileName.substringBeforeLast(".")
        // peel subtitle extension leftovers: "Movie.en.srt" -> handled by caller,
        // here handle "Movie.en" / "Movie.SubArabify.ar"
        base = base.replace(Regex("""(?i)[._\- ](en|eng|english|en-us|en-gb|ar|arabic|arab)$"""), "")
        base = base.replace(Regex("""(?i)[._\- ]?subarabify([._\- ]?ar)?$"""), "")
        return base
    }

    /**
     * Normalize a video base for fuzzy matching: lowercase, strip year
     * "(2023)"/" 2023", resolution/source tags (1080p, WEB-DL, BluRay, x264…),
     * separators → single space.
     */
    fun fuzzyKey(name: String): String {
        var s = smartBase(name)
        s = s.replace(Regex("""[._\-]+"""), " ")
        s = s.replace(Regex("""\s*[\(\[]\d{4}[\)\]]\s*"""), " ")
        s = s.replace(Regex("""\s+\d{4}\s*"""), " ")
        s = s.replace(
            Regex("""(?i)\b(1080p|720p|480p|2160p|4k|8k|web[\- ]?dl|webrip|bluray|blu[\- ]?ray|bdrip|dvdrip|hdtv|hdr|hdr10|dolby|atmos|x264|x265|hevc|aac|dts|yts|yify|rarbg|ettv|eztv|proper|repack|extended|unrated|remastered|multi|v2|final)\b"""),
            " ",
        )
        return s.replace(Regex("""\s+"""), " ").trim().lowercase()
    }

    /**
     * Find the best English-ish subtitle for [videoBase] inside [folder].
     * Order: exact tagged names → exact bare → fuzzy same-folder match →
     * any-language fallback (translate whatever exists) → .vtt equivalents.
     * Never returns our own *.SubArabify.ar.srt outputs.
     */
    fun findBestSource(folder: DocumentFile, videoBase: String): DocumentFile? {
        val files = folder.listFiles().filter { it.isFile }
        if (files.isEmpty()) return null

        fun isOurs(n: String): Boolean {
            val l = n.lowercase()
            return l.contains("subarabify") || l.endsWith(".ar.srt") || l.endsWith(".ar.vtt")
        }

        // 1. Exact tagged / bare names (srt + vtt)
        val exactNames = listOf(
            "$videoBase.en.srt", "$videoBase.eng.srt", "$videoBase.English.srt",
            "$videoBase.en-US.srt", "$videoBase.en-GB.srt",
            "$videoBase.en.vtt", "$videoBase.eng.vtt",
            "$videoBase.srt", "$videoBase.vtt",
        )
        val byName = files.associateBy { it.name ?: "" }
        val exactTagged = exactNames.take(7).mapNotNull { byName[it] }.filter { !isOurs(it.name.orEmpty()) }
        if (exactTagged.isNotEmpty()) {
            return exactTagged.maxByOrNull { it.length() }
        }
        byName["$videoBase.srt"]?.let { if (!isOurs(it.name.orEmpty())) return it }
        byName["$videoBase.vtt"]?.let { if (!isOurs(it.name.orEmpty())) return it }

        val wantFuzzy = fuzzyKey(videoBase)

        // 2. Fuzzy: subtitle whose smart-base fuzzy-matches the video
        val fuzzySubs = files.filter { f ->
            val n = f.name ?: return@filter false
            if (isOurs(n) || !isSubtitleFile(n)) return@filter false
            fuzzyKey(n.substringBeforeLast(".")) == wantFuzzy ||
                smartBase(n.substringBeforeLast(".")).equals(videoBase, ignoreCase = true)
        }
        if (fuzzySubs.isNotEmpty()) {
            // prefer English-tagged, then largest
            return fuzzySubs.maxWithOrNull(
                compareBy<DocumentFile> { f ->
                    val l = f.name?.lowercase().orEmpty()
                    when {
                        l.contains(".en.") || l.contains(".eng.") || l.contains(".english.") -> 2
                        l.endsWith(".srt") -> 1
                        else -> 0
                    }
                }.thenBy { it.length() }
            )
        }

        // 3. Any-language fallback: any .srt/.vtt sharing the fuzzy base words
        // (e.g. "Movie.French.srt" — better to translate French→(as EN) than nothing;
        // ML Kit EN model handles Latin-script sources gracefully, else cue passes through)
        val wantWords = wantFuzzy.split(" ").filter { it.length > 2 }.toSet()
        if (wantWords.isNotEmpty()) {
            val loose = files.filter { f ->
                val n = f.name ?: return@filter false
                if (isOurs(n) || !isSubtitleFile(n)) return@filter false
                val fk = fuzzyKey(n.substringBeforeLast("."))
                wantWords.any { w -> fk.contains(w) }
            }
            if (loose.isNotEmpty()) {
                return loose.maxByOrNull { it.length() }
            }
        }
        return null
    }
}
