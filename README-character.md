# TINA AI — 2D Character (Milestone 3)

Adds TINA herself: a reaction engine that decides what she should express,
and a view that renders it. This is the "Layer 1 — instant rules" from the
product doc — cheap, local, zero AI-call latency.

## ⚠️ What you still need: the actual art

I built the *system*, not the *character*. `TinaCharacterView` looks for a
Lottie animation file per expression at `app/src/main/assets/tina/*.json`
(see the filenames in `TinaExpression.kt`). **None of those files exist
yet** — until you add them, the view falls back to a colored circle +
emoji, so you can test and tune the reaction logic today with zero art,
then drop in real animations later without touching any Kotlin code.

Options for getting the actual animations, roughly cheapest to most
production-ready:
1. **LottieFiles.com** — free marketplace, search "girl character
   expressions" / "avatar reactions" — grab ones close enough to start,
   swap for custom later
2. **Commission an animator** — for the real illustrated-girl look from
   your mockup, this needs someone who can rig and export to Lottie (or
   Rive, if you'd rather switch formats)
3. **Rive instead of Lottie** — better for character state machines
   specifically (blending between expressions, not just cutting) — worth
   evaluating if Lottie's per-clip model feels too rigid once you're
   actually building this out

Whichever you pick, filenames just need to match `TinaExpression.kt`'s
`assetFileName` values, dropped into `app/src/main/assets/tina/`.

## New files

- `TinaExpression.kt` — the 14 expression states from the product doc,
  each mapped to a Lottie filename + emoji fallback
- `TinaReactionEngine.kt` — Layer 1 rules: laugh/question/confusion/excited
  keyword matching on caption text, plus a 12s silence timer that triggers
  a "thinking" expression (the actual ice-breaker suggestion text is a
  Layer 3/cloud concern — this only handles TINA's *expression*)
- `TinaCharacterView.kt` — Compose view, Lottie if the asset exists,
  emoji-in-a-circle fallback if not

## How it's wired

Caption events (from Milestone 2) already flow through `CallViewModel` —
the reaction engine just taps into the same stream:

```
Peer's translated caption arrives → reactionEngine.onSpeechFinalized(...)
My own speech finalizes           → reactionEngine.onSpeechFinalized(...)
Either side starts talking        → reactionEngine.onSpeechStarted()
                                     ↓
                            TinaExpression emitted
                                     ↓
                          TinaCharacterView re-renders
```

## Current rules (tune freely — they're intentionally simple to start)

- Laugh keywords (`haha`, `lol`, 😂...) → LAUGHING
- Confusion phrases → CONFUSED
- Excitement phrases (`wow`, `omg`...) → EXCITED
- Ends in `?` → CURIOUS (if I asked) or LISTENING (if peer asked)
- Otherwise → HAPPY (light positive default), reverting to IDLE after 2.5s
- 12s of silence → THINKING (stays until someone speaks again)

These are all string-matching on the *translated* caption text, so they
work regardless of which language either person is speaking. Matching is
English-keyword-based right now (`haha`, `wow`, etc.) — since captions are
translated into each viewer's language before reaching this engine, you'll
want to either match on the *pre-translation* text in the speaker's own
language, or run these checks against multiple languages. Worth deciding
once you see real conversations.

## Layer 2/3 hook point

The doc's Layer 2 (small on-device model) and Layer 3 (cloud) are meant to
add richer signals eventually — sentiment, "explain this reference",
topic suggestions. When you build those, they should call the same
`onExpressionChange` path (or a new method on `TinaReactionEngine`) rather
than bypassing it, so there's one place that owns "what is TINA doing
right now."

## Next milestone

Your call — from here:
1. **Real animation assets** (see above) so this actually looks like TINA
2. **Speech synthesis** — TINA/the peer's voice speaking the translation
3. **Cloud mediation layer** — topic suggestions, cultural reference
   explanations, using Claude (Talksy already uses Claude for post-call
   feedback — same API, different prompt)
