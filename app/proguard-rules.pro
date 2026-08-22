# Placeholder — isMinifyEnabled is currently false in build.gradle.kts, so
# this isn't exercised yet. When you turn minification on for release
# builds, WebRTC, Firebase, and ML Kit each typically need keep rules —
# check each library's own docs for current recommended rules rather than
# guessing, since these change across versions:
#   - io.getstream:stream-webrtc-android
#   - Firebase (RTDB + Auth)
#   - com.google.mlkit:translate
