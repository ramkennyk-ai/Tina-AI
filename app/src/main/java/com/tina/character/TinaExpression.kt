package com.tina.character

/**
 * TINA's expression set — matches the product doc's list. Each maps to a
 * Lottie animation filename under assets/tina/. Until you have real
 * animation files, TinaCharacterView falls back to an emoji + color so the
 * reaction LOGIC is testable without any art yet.
 */
enum class TinaExpression(val assetFileName: String, val fallbackEmoji: String) {
    IDLE("tina_idle.json", "😐"),
    HAPPY("tina_happy.json", "😊"),
    LAUGHING("tina_laughing.json", "😂"),
    SURPRISED("tina_surprised.json", "😮"),
    CURIOUS("tina_curious.json", "🤔"),
    TEASING("tina_teasing.json", "😏"),
    THINKING("tina_thinking.json", "🤔"),
    EXCITED("tina_excited.json", "🤩"),
    CONFUSED("tina_confused.json", "😕"),
    ENCOURAGING("tina_encouraging.json", "👍"),
    SHY("tina_shy.json", "😳"),
    SAD("tina_sad.json", "😢"),
    JOKING("tina_joking.json", "😉"),
    LISTENING("tina_listening.json", "👀")
}
