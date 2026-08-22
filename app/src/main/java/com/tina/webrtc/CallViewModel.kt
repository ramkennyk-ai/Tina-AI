package com.tina.webrtc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tina.character.TinaExpression
import com.tina.character.TinaReactionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.SurfaceViewRenderer

enum class CallState { IDLE, CONNECTING, IN_CALL, ENDED, FAILED }

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    private val _micEnabled = MutableStateFlow(true)
    val micEnabled: StateFlow<Boolean> = _micEnabled

    private val _cameraEnabled = MutableStateFlow(true)
    val cameraEnabled: StateFlow<Boolean> = _cameraEnabled

    // What I'm saying, recognized in my language (shown small, my own caption)
    private val _myLiveCaption = MutableStateFlow("")
    val myLiveCaption: StateFlow<String> = _myLiveCaption

    // What the peer said, already translated into my language (shown large)
    private val _peerCaption = MutableStateFlow("")
    val peerCaption: StateFlow<String> = _peerCaption

    private val _tinaExpression = MutableStateFlow(TinaExpression.IDLE)
    val tinaExpression: StateFlow<TinaExpression> = _tinaExpression

    private var reactionEngine: TinaReactionEngine? = null

    private var manager: TinaWebRTCManager? = null
    private var speechRecognizer: SpeechRecognitionManager? = null
    private var translator: TranslationManager? = null

    /**
     * @param roomId shared by both peers — same convention as Talksy
     * @param isCaller room creator = caller
     * @param myLanguageTag BCP-47 for MY speech recognition, e.g. "en-IN"
     * @param myBaseLanguage ML Kit base code for MY spoken language, e.g. "en"
     * @param peerBaseLanguage ML Kit base code for the PEER's language, e.g. "ru" —
     *   translation runs source=myBaseLanguage → target=peerBaseLanguage, so
     *   what I say gets sent to them already translated into their language.
     *   (In production, exchange each side's chosen language via the RTDB
     *   room metadata before the call starts, rather than hardcoding it.)
     */
    fun startCall(
        roomId: String,
        isCaller: Boolean,
        myLanguageTag: String,
        myBaseLanguage: String,
        peerBaseLanguage: String,
        localRenderer: SurfaceViewRenderer,
        remoteRenderer: SurfaceViewRenderer
    ) {
        _callState.value = CallState.CONNECTING

        manager = TinaWebRTCManager(
            context = getApplication(),
            roomId = roomId,
            isCaller = isCaller,
            onCallEnded = {
                _callState.value = CallState.ENDED
                stopCaptioning()
            },
            onCallConnected = {
                _callState.value = CallState.IN_CALL
                startCaptioning(myLanguageTag, myBaseLanguage, peerBaseLanguage)
                reactionEngine = TinaReactionEngine(viewModelScope) { _tinaExpression.value = it }
                    .also { it.resetSilenceTimer() }
            }
        ).also {
            it.setOnCaptionReceived { translatedText ->
                _peerCaption.value = translatedText
                reactionEngine?.onSpeechFinalized(translatedText, isPeerSpeaking = true)
            }
            it.init(localRenderer, remoteRenderer)
        }
    }

    private fun startCaptioning(myLanguageTag: String, myBaseLanguage: String, peerBaseLanguage: String) {
        translator = TranslationManager(
            sourceLanguageTag = myBaseLanguage,
            targetLanguageTag = peerBaseLanguage,
            onModelDownloadFailed = { /* fall back to no captions for this call; consider a retry/toast */ }
        ).also { it.prepare(requireWifi = true) }

        speechRecognizer = SpeechRecognitionManager(
            context = getApplication(),
            languageTag = myLanguageTag,
            onPartialResult = {
                _myLiveCaption.value = it
                reactionEngine?.onSpeechStarted()
            },
            onFinalResult = { finalText ->
                _myLiveCaption.value = finalText
                reactionEngine?.onSpeechFinalized(finalText, isPeerSpeaking = false)
                if (translator?.isReady() == true) {
                    translator?.translate(
                        text = finalText,
                        onResult = { translated -> manager?.sendCaption(translated) }
                    )
                }
            }
        ).also { it.start() }
    }

    private fun stopCaptioning() {
        speechRecognizer?.stop()
        translator?.close()
        reactionEngine?.stop()
        speechRecognizer = null
        translator = null
        reactionEngine = null
    }

    fun toggleMic() {
        val newState = !_micEnabled.value
        manager?.setMicEnabled(newState)
        _micEnabled.value = newState
    }

    fun toggleCamera() {
        val newState = !_cameraEnabled.value
        manager?.setCameraEnabled(newState)
        _cameraEnabled.value = newState
    }

    fun switchCamera() {
        manager?.switchCamera()
    }

    fun endCall() {
        manager?.hangUp()
        stopCaptioning()
        _callState.value = CallState.ENDED
    }

    override fun onCleared() {
        super.onCleared()
        manager?.cleanup()
        stopCaptioning()
    }
}
