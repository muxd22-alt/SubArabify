package com.subarabify.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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
            if (line.trim().isEmpty() || line.all { it.isDigit() }) {
                line
            } else {
                try {
                    translator.translate(line).await()
                } catch (e: Exception) {
                    line
                }
            }
        }
    }

    fun close() {
        translator.close()
    }
}
