package com.tina.webrtc

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps Android's on-device SpeechRecognizer for near-continuous captioning:
 * it restarts itself after each result/silence so it keeps listening for the
 * rest of the call, rather than stopping after one utterance.
 *
 * ⚠️ KNOWN LIMITATION: SpeechRecognizer and WebRTC's audio capture both want
 * the microphone. Running both at once is unreliable on some devices —
 * you may see dropped recognition, audio glitches, or one silently losing
 * mic access. Test on your real target devices (Samsung/Redmi, Android 14+)
 * before relying on this in production. If it proves too flaky, the more
 * robust (but more work) path is tapping WebRTC's captured audio frames
 * directly via a custom AudioDeviceModule and feeding those to a streaming
 * cloud STT service instead of a second independent mic listener.
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val languageTag: String, // BCP-47, e.g. "en-IN", "hi-IN", "ru-RU"
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldKeepListening = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }
        shouldKeepListening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        listenOnce()
    }

    private fun listenOnce() {
        if (!shouldKeepListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // stays on-device where supported
        }
        isListening = true
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: android.os.Bundle) {
            isListening = false
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { onFinalResult(it) }
            listenOnce() // keep the caption stream going
        }

        override fun onPartialResults(partialResults: android.os.Bundle) {
            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { onPartialResult(it) }
        }

        override fun onError(error: Int) {
            isListening = false
            // NO_MATCH / SPEECH_TIMEOUT are routine (silence) — just keep going
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.w("SpeechRecognition", "Error code: $error")
            }
            listenOnce()
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    fun stop() {
        shouldKeepListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }
}
