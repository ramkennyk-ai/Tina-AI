package com.tina.webrtc

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device translation via ML Kit. Models are downloaded once per language
 * pair (a few hundred MB total for common pairs) and cached on the device —
 * no network needed for translation itself after that, which keeps this in
 * TINA's "local reflexes" layer rather than the cloud layer.
 */
class TranslationManager(
    sourceLanguageTag: String, // BCP-47, e.g. "en", "hi", "ru" (ML Kit uses base language, not region)
    targetLanguageTag: String,
    private val onModelReady: () -> Unit = {},
    private val onModelDownloadFailed: (Exception) -> Unit = {}
) {
    private val sourceLang = TranslateLanguage.fromLanguageTag(sourceLanguageTag) ?: TranslateLanguage.ENGLISH
    private val targetLang = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: TranslateLanguage.ENGLISH

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
    )

    private var modelDownloaded = false

    /** Call once before translating — downloads the model if not already cached. */
    fun prepare(requireWifi: Boolean = true) {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                modelDownloaded = true
                onModelReady()
            }
            .addOnFailureListener { onModelDownloadFailed(it) }
    }

    fun translate(text: String, onResult: (String) -> Unit, onError: (Exception) -> Unit = {}) {
        if (text.isBlank()) return
        translator.translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onError(it) }
    }

    fun isReady(): Boolean = modelDownloaded

    fun close() {
        translator.close()
    }
}
