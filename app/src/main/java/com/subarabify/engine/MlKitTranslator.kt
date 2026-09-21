package com.subarabify.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.subarabify.data.SrtParser
import kotlinx.coroutines.tasks.await

class MlKitTranslator {

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.ARABIC)
        .build()

    private val translator = Translation.getClient(options)

    suspend fun prepareModel(): Boolean {
        return try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun translateBatch(lines: List<String>): List<String> {
        return lines.map { line ->
            val clean = SrtParser.stripMarkup(line)
            if (clean.isEmpty() || clean.all { it.isDigit() }) {
                clean
            } else {
                try {
                    translator.translate(clean).await()
                } catch (e: Exception) {
                    clean
                }
            }
        }
    }

    fun close() {
        translator.close()
    }
}
