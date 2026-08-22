# TINA AI — Live Translated Captions (Milestone 2)

Builds on Milestone 1 (WebRTC P2P calling). Adds: on-device speech
recognition of what you say → on-device translation into the peer's
language → sent to them over a WebRTC data channel → shown as a caption
on their screen. Full text pipeline, no cloud call needed for this piece.

## New files

- `SpeechRecognitionManager.kt` — wraps Android's SpeechRecognizer, restarts
  itself continuously so it approximates live captioning
- `TranslationManager.kt` — ML Kit on-device translation, handles model
  download
- Data channel added to `TinaWebRTCManager.kt` — caller creates a
  `"captions"` DataChannel, callee receives it via `onDataChannel`; both
  sides can then send/receive caption text with call-grade latency

## ⚠️ Read this before you build on it

**Microphone contention.** `SpeechRecognizer` and WebRTC's audio capture
both want the mic. This build runs them side by side, which is unreliable
on some devices — you may see dropped recognition or audio glitches.
**Test on your actual Samsung/Redmi devices early.** If it's too flaky in
practice, the fix is deeper: intercept WebRTC's already-captured audio
frames (via a custom `JavaAudioDeviceModule` audio samples callback) and
feed those frames to a streaming STT engine, instead of giving
SpeechRecognizer its own independent mic session. That's a bigger change —
worth doing only once you've confirmed the simple version isn't good enough.

**`EXTRA_PREFER_OFFLINE` isn't a guarantee.** Whether recognition actually
stays on-device depends on the OEM's speech service and whether the
language pack is downloaded. Some Samsung/Redmi devices route to Google's
cloud speech service regardless. If fully offline recognition matters for
cost/latency, this is a good candidate to swap out later (e.g. Android's
`SpeechRecognizer.createOnDeviceSpeechRecognizer()` where supported, or a
dedicated on-device STT model).

**Translation quality on short fragments.** Partial/live SpeechRecognizer
results are noisy. This pipeline only translates *final* results (after a
pause), not every partial update — the partials are shown as your own
"what I'm saying" caption, not translated, so you're not paying for
translation calls on every keystroke-like update.

## How it flows

```
You speak
   ↓
SpeechRecognizer (your language)
   ↓ (on final result)
TranslationManager (your language → peer's language, on-device)
   ↓
WebRTC data channel "captions"
   ↓
Peer's screen shows translated caption
```

Same thing runs in both directions — each side runs its own
SpeechRecognizer + Translator instance for their own speech.

## Language setup (you'll need to decide this)

Right now `CallScreen` takes `myLanguageTag`, `myBaseLanguage`, and
`peerBaseLanguage` as parameters — hardcode them for testing, but in
practice each user should pick their spoken language once (e.g. in a
profile screen), and you exchange both peers' choices via the RTDB room
metadata before the call starts, similar to how you already exchange
offer/answer. Example RTDB addition:

```
rooms/{roomId}/participants/{uid}/language: "hi"
```

## Model downloads

ML Kit translation models are ~30-40MB per language and cached after first
download. `TranslationManager.prepare(requireWifi = true)` blocks
translation until the model's ready — for a good first-call experience,
consider pre-downloading the user's chosen language pair as soon as they
pick it in settings, rather than waiting until they're mid-call.

## Gradle

Added to `app/build.gradle.kts.snippet`:
```kotlin
implementation("com.google.mlkit:translate:17.0.3")
```

## Next milestone

Options from here, your call:
1. **TINA's character** — start simple (animated 2D reactions to caption
   sentiment/pauses) rather than jumping straight to full 3D
2. **Speech synthesis** — replace/augment captions with TTS in the peer's
   voice-language, so they hear it instead of reading it
3. **Cultural mediation logic** — the "explain this meme/reference" layer,
   which needs cloud AI (Claude) since it needs current context
