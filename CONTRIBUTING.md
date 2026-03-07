# Contributing to GroqAndroid

Thanks for your interest in contributing! Here's how to get started.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/GroqAndroid.git`
3. Open in Android Studio and let Gradle sync
4. Create a branch: `git checkout -b feature/your-feature`

## Development Setup

- **JDK**: 17 (Temurin recommended)
- **Android SDK**: API 35 (Android 15)
- **Min SDK**: 26 (Android 8.0)
- **IDE**: Android Studio (latest stable)

## Code Style

- **Language**: Kotlin
- **Formatting**: Default Android Studio / ktlint settings
- **Error handling**: Wrap `currentInputConnection` and `EncryptedSharedPreferences` calls in try/catch
- **No unused imports**: Clean up before committing

## Pull Request Guidelines

1. **One feature/fix per PR** — keep changes focused
2. **Descriptive title** — e.g., "Add haptic feedback on mic tap" not "Update GroqIME.kt"
3. **Test on a real device** — emulators don't fully replicate IME behavior
4. **Include a summary** — what changed and why
5. **Screenshots/video** if UI changed

## Branch Naming

- `feature/description` — new features
- `fix/description` — bug fixes
- `docs/description` — documentation only

## Testing

- Build with `./gradlew assembleDebug`
- Test IME activation, recording, transcription, and keyboard switching
- Test on multiple Android versions if possible (especially API 26 and latest)
- Verify on Xiaomi/MIUI if you have access (known quirks)

## Reporting Issues

- Use GitHub Issues
- Include: device model, Android version, steps to reproduce
- Attach logs if possible (`adb logcat -s GroqAndroid`)

## Architecture Notes

See [CLAUDE.md](CLAUDE.md) for detailed architecture documentation, including:
- File structure and responsibilities
- Known issues and design decisions
- Recording flow and keyboard layout
