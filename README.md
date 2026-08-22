# TINA AI — WebRTC P2P Video Calling (Milestone 1)

This is the calling core only — no translation, no AI, no 3D character yet.
Goal: two phones can see and hear each other over a direct P2P connection.
Built on the same signaling approach as Talksy (`RealtimeDBSignalingManager`
over Firebase RTDB), extended from audio-only to video.

## Files

- `RealtimeDBSignalingManager.kt` — Firebase RTDB signaling: offer/answer/ICE
  exchange under `rooms/{roomId}`
- `TinaWebRTCManager.kt` — Talksy's WebRTCManager pattern (network-adaptive
  Opus, ICE timeout/disconnect handling, TURN fallback) + video capture/tracks
- `CallViewModel.kt` — wires the manager to Compose state
- `CallScreen.kt` — call UI (video surfaces + mic/camera/switch/end controls)
- `app/build.gradle.kts.snippet` — dependencies to merge into your real `build.gradle.kts`
- `AndroidManifest.snippet.xml` — permissions to merge into your real manifest

## How the pieces fit together

```
Phone A (caller)         Firebase RTDB          Phone B (callee)
   |  write callerOffer  |                          |
   |--------------------->|<---- listens for offer --|
   |                       |---- write calleeAnswer ->|
   |<-- listens for answer|                          |
   |  ICE candidates exchanged the same way          |
   |                                                  |
   |============ direct WebRTC media (P2P) ===========|
```

RTDB only ever carries small SDP/ICE JSON blobs. Media never touches it.

## Wiring it into your app

```kotlin
CallScreen(
    roomId = "some-shared-room-id",  // both peers use the same one
    isCaller = true,                 // false on the other device — Talksy's
                                      // convention: room creator = caller,
                                      // e.g. via alphabetical UID comparison
    onCallEnded = { /* navigate back */ }
)
```

## Reused from Talksy

- RTDB signaling shape (`rooms/{roomId}/...`) — same node structure
- Network-adaptive Opus bitrate (WiFi 48kbps / 4G 24kbps+FEC / other 16kbps+FEC)
- AEC2/NS/AGC audio constraints
- 30s ICE connect timeout, 15s disconnect grace period before ending the call
- STUN + your existing coturn TURN droplet as fallback — **swap
  `YOUR_DROPLET_IP` / `YOUR_PASSWORD` in `TinaWebRTCManager.kt` for your real
  Talksy TURN credentials** (or provision a separate one for TINA)

## New for TINA (video)

- Camera2 front-camera capture, 720p30
- Local + remote `SurfaceViewRenderer`s wired through `EglBase`
- `onTrack`/`onAddStream` route the remote video track to the remote renderer
- Camera enable/disable and front/back switch controls

## Firebase setup

1. Reuse Talksy's Firebase project or create a new one for TINA — your call.
2. Enable Realtime Database, add `google-services.json` to the app module.
3. Add the `google-services` Gradle plugin (see note at the bottom of
   `build.gradle.kts.snippet`).
4. RTDB security rules — start simple, tighten later:

```json
{
  "rules": {
    "rooms": {
      "$roomId": {
        ".read": "auth != null",
        ".write": "auth != null"
      }
    }
  }
}
```

Anonymous Auth (which Talksy already uses) is enough to satisfy `auth != null`.

## Building on GitHub Actions (mobile-only workflow)

Nothing here needs a local build step — `stream-webrtc-android` and the
Firebase SDKs are all precompiled artifacts from Maven Central/Google's
Maven repo, so `./gradlew assembleDebug` in Actions resolves them like any
other dependency.

## Testing

Two physical devices, same `roomId`, one launched as caller / one as callee.
Camera+mic permissions must be granted before `CallScreen` mounts — add a
permission-request step before navigating in if you don't have one already.

## What's deliberately NOT here yet

- Matchmaking/room-id generation and the caller/callee decision logic itself
  (reuse Talksy's alphabetical-UID approach, or your own matchmaking)
- Reconnection handling on backgrounding
- The 15-min free timer / rewarded ads
- Foreground service to keep the call alive backgrounded (stub left in the
  manifest snippet)
- TINA herself — captions, translation, the character

## Next milestone

Speech-to-text + translation pipeline, hooking into the same audio track
already captured here.
