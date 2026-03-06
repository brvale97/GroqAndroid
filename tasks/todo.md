# GroqAndroid - Spraak-naar-tekst Keyboard

## Implementation
- [x] Gradle project structure (build.gradle.kts, settings, wrapper)
- [x] AndroidManifest.xml with IME service, permissions
- [x] Resource files (layouts, drawables, strings, colors, method.xml)
- [x] AudioRecorder.kt - 16kHz mono PCM → WAV
- [x] GroqApiClient.kt - Multipart POST to Groq Whisper API
- [x] GroqIME.kt - InputMethodService with mic/switch buttons
- [x] SettingsActivity.kt - API key entry with EncryptedSharedPreferences
- [x] PermissionActivity.kt - RECORD_AUDIO runtime permission

## Verification (requires Android SDK)
- [ ] Project compiles without errors
- [ ] APK installs on device/emulator
- [ ] IME can be activated in Settings
- [ ] Audio recording works
- [ ] Groq API transcription returns text
- [ ] Text is committed to input field
- [ ] Keyboard switch works
