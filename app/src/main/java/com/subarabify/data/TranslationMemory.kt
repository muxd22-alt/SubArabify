package com.subarabify.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.LinkedHashMap

/**
 * Persistent translation memory: exact-match cache for EN→AR lines.
 *
 * Why this fixes "lags between runs": repeated subtitle lines
 * ("Yeah.", "Okay.", "Previously on…") are translated ONCE and then
 * served from disk on every later movie / re-scan. No network, no ML
 * inference, no waiting.
 */
class TranslationMemory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("subarabify_tm", Context.MODE_PRIVATE)

    private val lock = Any()

    // Small in-memory LRU on top of disk
    private val mem = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 800
        }
    }

    fun get(normalizedEn: String): String? {
        synchronized(lock) {
            mem[normalizedEn]?.let { return it }
            val v = prefs.getString(key(normalizedEn), null)
            if (v != null) mem[normalizedEn] = v
            return v
        }
    }

    fun put(normalizedEn: String, ar: String) {
        if (normalizedEn.isBlank() || ar.isBlank()) return
        synchronized(lock) {
            mem[normalizedEn] = ar
            prefs.edit { putString(key(normalizedEn), ar) }
        }
    }

    fun putAll(pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        synchronized(lock) {
            prefs.edit {
                for ((k, v) in pairs) {
                    if (k.isNotBlank() && v.isNotBlank()) {
                        mem[k] = v
                        putString(key(k), v)
                    }
                }
            }
        }
    }

    fun size(): Int = prefs.all.size

    companion object {
        fun normalize(line: String): String {
            return SrtParser.stripMarkup(line)
                .replace(Regex("""\s+"""), " ")
                .trim()
                .lowercase()
        }

        private fun key(normalized: String): String = "tm_" + normalized.hashCode()
    }
}
