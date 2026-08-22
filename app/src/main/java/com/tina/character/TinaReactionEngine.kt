package com.tina.character

import kotlinx.coroutines.*

/**
 * TINA's "Layer 1" from the product doc: instant, essentially-free reactions
 * driven by simple rules — no AI model call, so this never adds latency.
 * Layer 2 (small on-device model) and Layer 3 (cloud) can later feed richer
 * signals into this same engine via `reactTo(...)`; for now it works purely
 * off caption text and silence duration, which we already have from the
 * captioning pipeline.
 */
class TinaReactionEngine(
    private val scope: CoroutineScope,
    private val onExpressionChange: (TinaExpression) -> Unit
) {
    private var currentExpression = TinaExpression.IDLE
    private var silenceJob: Job? = null
    private var revertJob: Job? = null

    private val SILENCE_THRESHOLD_MS = 12_000L   // long pause → TINA looks curious/prompts
    private val REACTION_HOLD_MS = 2_500L         // how long a reaction expression stays before reverting to idle/listening

    private val laughPatterns = listOf("haha", "hahaha", "lol", "lmao", "😂", "hehe")
    private val questionEndings = listOf("?")
    private val excitedPatterns = listOf("wow", "amazing", "awesome", "no way", "really?!", "omg")
    private val confusedPatterns = listOf("what do you mean", "i don't understand", "sorry what", "huh")

    /** Call this every time either side's caption text finalizes. */
    fun onSpeechFinalized(text: String, isPeerSpeaking: Boolean) {
        resetSilenceTimer()
        val lower = text.lowercase()

        val expression = when {
            laughPatterns.any { lower.contains(it) } -> TinaExpression.LAUGHING
            confusedPatterns.any { lower.contains(it) } -> TinaExpression.CONFUSED
            excitedPatterns.any { lower.contains(it) } -> TinaExpression.EXCITED
            questionEndings.any { text.trim().endsWith(it) } ->
                if (isPeerSpeaking) TinaExpression.LISTENING else TinaExpression.CURIOUS
            else -> TinaExpression.HAPPY // default light-positive reaction to normal conversation
        }

        setExpression(expression, autoRevert = true)
    }

    /** Call this whenever either side starts speaking (partial results arriving). */
    fun onSpeechStarted() {
        resetSilenceTimer()
        if (currentExpression == TinaExpression.IDLE) {
            setExpression(TinaExpression.LISTENING, autoRevert = false)
        }
    }

    /** Call this once when the call connects, and any time you want the silence clock reset. */
    fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_THRESHOLD_MS)
            // Long silence — TINA can break the ice. The actual topic suggestion
            // text is a Layer 3 (cloud) concern; this just triggers the expression.
            setExpression(TinaExpression.THINKING, autoRevert = false)
        }
    }

    private fun setExpression(expression: TinaExpression, autoRevert: Boolean) {
        revertJob?.cancel()
        currentExpression = expression
        onExpressionChange(expression)

        if (autoRevert) {
            revertJob = scope.launch {
                delay(REACTION_HOLD_MS)
                currentExpression = TinaExpression.IDLE
                onExpressionChange(TinaExpression.IDLE)
            }
        }
    }

    fun stop() {
        silenceJob?.cancel()
        revertJob?.cancel()
    }
}
