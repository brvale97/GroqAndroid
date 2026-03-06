<p align="center">
  <img src="app/src/main/res/drawable/ic_app.xml" alt="GroqAndroid" width="80" />
</p>

<h1 align="center">GroqAndroid</h1>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?style=flat" alt="Platform" />
  <img src="https://img.shields.io/badge/minSdk-26-blue?style=flat" alt="Min SDK" />
  <a href="https://github.com/brvale97/GroqAndroid/releases/latest"><img src="https://img.shields.io/github/v/release/brvale97/GroqAndroid?style=flat&sort=semver" alt="GitHub release" /></a>
  <a href="https://github.com/brvale97/GroqAndroid/releases"><img src="https://img.shields.io/github/downloads/brvale97/GroqAndroid/total?style=flat&color=blue" alt="Downloads" /></a>
  <a href="https://github.com/brvale97/GroqAndroid/stargazers"><img src="https://img.shields.io/github/stars/brvale97/GroqAndroid?style=flat" alt="GitHub stars" /></a>
</p>

<p align="center">
  A minimalist Android speech-to-text keyboard and floating voice input bubble, powered by the Groq Whisper API.<br/>
  Transcribe speech directly into any app — no cloud account needed, just a free API key.
</p>

---

## Features

### Speech-to-Text Keyboard

- **Tap to dictate**: Tap the microphone, speak, tap again — transcribed text appears instantly
- **16 languages**: Auto-detect or choose from Dutch, English, German, French, Spanish, Italian, Portuguese, Japanese, Chinese, Korean, Arabic, Turkish, Polish, Russian, and Slovak
- **Custom dictionary**: Add names, jargon, and technical terms for better recognition
- **Word replacements**: Auto-correct frequently misrecognized words
- **Punctuation keys**: Quick access to `, . ? !` and common symbols
- **Quotes wrapping**: Select text and tap `" "` to wrap it in quotes
- **Select all**: Long-press spacebar to select all text
- **Continuous backspace**: Hold backspace for rapid deletion

### Floating Voice Bubble

- **Use with any keyboard**: Keep Gboard (or any keyboard) active — the floating bubble handles voice input independently
- **Works in any app**: WhatsApp, Signal, SimpleNote, browsers, and more
- **Draggable**: Move the bubble anywhere on screen, snaps to edges
- **Long-press to close**: Hold the bubble to reveal a close button
- **Notification controls**: Re-show the bubble or stop the service from the notification
- **Auto-show in chats** (optional): Automatically appear when a text field is focused, hide when leaving
- **Clipboard fallback**: For apps that don't support direct text insertion (like Claude), text is copied to clipboard for manual paste

### Audio Cues

- **Dictation sounds**: Hear a short cue when recording starts and stops (matching [OpenWhispr](https://github.com/OpenWhispr/openwhispr) sounds)
- **Toggleable**: Enable or disable sound cues in settings

### Whisper Model Selection

- **V3 Turbo**: Faster transcription, great for everyday use
- **V3 Full**: Higher accuracy, better for difficult audio or accents

### Privacy & Security

- **API key stored encrypted**: Uses Android's EncryptedSharedPreferences (AES-256)
- **No accounts**: Just a free Groq API key from [console.groq.com](https://console.groq.com)
- **No telemetry**: No data collection, no analytics
- **Open source**: All code available for review

## Download

Get the latest APK from the [Releases page](https://github.com/brvale97/GroqAndroid/releases/latest).

> **Note**: You'll need to allow "Install from unknown sources" on your Android device.

## Setup

### 1. Install the APK

Download and install `GroqAndroid-v1.5.apk` from the releases page.

### 2. Get a Groq API key

1. Go to [console.groq.com](https://console.groq.com)
2. Sign up for a free account
3. Create an API key

### 3. Configure the app

1. Open GroqAndroid
2. Paste your API key and tap **Save**
3. Follow the on-screen steps to enable the keyboard

### 4. Activate the keyboard

1. **Enable**: Settings → System → Languages & input → On-screen keyboard → Enable "GroqAndroid Voice"
2. **Select**: Open any text field, tap the keyboard icon in the navigation bar, and choose "GroqAndroid Voice"

> **Tip**: In Gboard, long-press the spacebar to quickly switch to GroqAndroid Voice.

### 5. Set up the floating bubble (optional)

1. Enable the **Floating Voice Input** toggle in the GroqAndroid app
2. When prompted, enable the **Accessibility Service** in Android settings
3. Grant **overlay permission** when prompted
4. Grant **notification permission** to see bubble controls in the notification shade

## Usage

### Keyboard Mode

1. Switch to GroqAndroid Voice keyboard
2. Tap the **microphone** button (turns red)
3. Speak your text
4. Tap again to stop (turns orange while processing)
5. Transcribed text appears at cursor with a trailing space

### Floating Bubble Mode

1. Enable the floating bubble in settings
2. A blue microphone icon appears on screen
3. **Tap** to start recording (turns red)
4. **Tap** again to stop (turns orange, then back to blue)
5. Text is inserted at the cursor in the currently focused text field
6. **Drag** the bubble to reposition it (snaps to screen edge)
7. **Long-press** to show the close button

### Language Selection

Tap the **LANG** button on the keyboard to choose from 16 languages or auto-detect.

## Keyboard Layout

```
            [status text / transcription result]
  [LANG]           [🎤 MIC button]           [⚙ Settings]
  [ ,  ] [ .  ] [  ?  ] [  !  ] [  ⌫ Backspace  ]
  [ ⌨  ] [       Spacebar        ] [ " " ] [  ↵  ]
```

| Button | Tap | Long press |
|--------|-----|------------|
| 🎤 Mic | Start/stop recording | — |
| `,` | Insert comma | Insert semicolon |
| `.` | Insert period | Insert colon |
| `?` | Insert question mark | — |
| `!` | Insert exclamation mark | — |
| ⌫ | Delete one character | Continuous delete |
| Spacebar | Insert space | Select all text |
| `" "` | Wrap selection in quotes | — |
| ↵ | New line | — |
| LANG | Language picker | — |
| ⌨ | Switch keyboard | — |
| ⚙ | Open settings | — |

## Settings

| Setting | Description | Default |
|---------|-------------|---------|
| **API Key** | Your Groq API key (encrypted storage) | — |
| **Custom Dictionary** | Words/names for better recognition | Empty |
| **Word Replacements** | Auto-correct misrecognized words | Empty |
| **Floating Voice Input** | Enable the floating bubble | Off |
| **Auto-show in chats** | Show/hide bubble with text field focus | Off |
| **Sound Cues** | Audio feedback on record start/stop | On |
| **Whisper Model** | V3 Turbo (fast) or V3 Full (accurate) | V3 Turbo |

## Architecture

```
app/src/main/java/com/groqandroid/
├── GroqIME.kt                    # Keyboard service (InputMethodService)
├── TranscriptionOverlayService.kt # Floating bubble (AccessibilityService)
├── GroqApiClient.kt               # Groq Whisper API client (OkHttp)
├── AudioRecorder.kt               # Microphone → WAV file (16kHz mono PCM)
├── SettingsActivity.kt            # Settings screen (launcher activity)
└── PermissionActivity.kt         # Runtime permission request
```

### How it works

1. **Recording**: `AudioRecorder` captures audio at 16kHz, mono, 16-bit PCM and writes a WAV file
2. **Transcription**: `GroqApiClient` sends the WAV to the Groq Whisper API via multipart POST
3. **Text insertion**:
   - **Keyboard**: `currentInputConnection.commitText()` inserts text directly
   - **Bubble**: `AccessibilityNodeInfo.ACTION_SET_TEXT` sets the field content, with clipboard paste as fallback
4. **Post-processing**: Word replacements are applied via regex before insertion

### Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **API**: Groq Whisper (`whisper-large-v3-turbo` / `whisper-large-v3`)
- **HTTP**: OkHttp with Kotlin coroutines
- **Audio**: AudioRecord (16kHz, mono, PCM 16-bit → WAV)
- **Security**: EncryptedSharedPreferences (AES-256-GCM)
- **Build**: Gradle with Kotlin DSL, AGP 8.7.x

## Building from Source

### Prerequisites

- Android Studio (Arctic Fox or later)
- JDK 17
- Android SDK 35

### Build & Run

```bash
# Clone the repository
git clone https://github.com/brvale97/GroqAndroid.git
cd GroqAndroid

# Open in Android Studio and sync Gradle
# Then build:
./gradlew assembleDebug

# Install on connected device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Generate Signed Release APK

```bash
# Build release
./gradlew assembleRelease

# Sign with your keystore
apksigner sign --ks your-keystore.jks \
  --ks-key-alias your-alias \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## Known Issues & Design Decisions

- **Fullscreen mode disabled**: `onEvaluateFullscreenMode()` returns `false` to prevent the keyboard from taking over the entire screen on some devices
- **Keyboard switch uses picker**: `switchToNextInputMethod()` crashes on some devices (especially Xiaomi/MIUI), so we use `showInputMethodPicker()` instead
- **MIUI overlay workaround**: Xiaomi/MIUI doesn't deliver touch events for `TYPE_ACCESSIBILITY_OVERLAY`, so the bubble uses `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW` permission
- **Short recording protection**: Recordings under ~1 second are skipped to prevent Whisper from hallucinating text based on the dictionary prompt
- **Hint text detection**: Signal and WhatsApp return placeholder text as `node.text` when the field is empty — we detect and filter this out

## FAQ

**Q: Is GroqAndroid free?**
A: Yes! The app is open source and free. You need a Groq API key, which has a generous free tier.

**Q: What's the difference between the keyboard and the floating bubble?**
A: The keyboard replaces your current keyboard entirely. The floating bubble works on top of any keyboard (like Gboard), so you can type normally and use voice input when needed.

**Q: Why doesn't text insertion work in the Claude app?**
A: Some apps (like Claude) use WebView-based text fields that don't support Android's accessibility text insertion. The transcribed text is copied to your clipboard — just long-press and paste.

**Q: Why does Whisper sometimes transcribe random text when I didn't say anything?**
A: Very short recordings (< 1 second) can cause Whisper to "hallucinate" text. The app now skips recordings that are too short.

**Q: Can I use this without an internet connection?**
A: No, GroqAndroid requires an internet connection to send audio to the Groq Whisper API. The audio is processed in the cloud and the transcribed text is returned.

**Q: Which languages are supported?**
A: 16 languages: Auto-detect, Dutch, English, German, French, Spanish, Italian, Portuguese, Japanese, Chinese, Korean, Arabic, Turkish, Polish, Russian, and Slovak.

## Acknowledgments

- **[Groq](https://groq.com/)** — Ultra-fast AI inference platform powering the Whisper transcription
- **[OpenAI Whisper](https://github.com/openai/whisper)** — The speech recognition model
- **[OpenWhispr](https://github.com/OpenWhispr/openwhispr)** — Inspiration for dictation cue sounds
- **[OkHttp](https://square.github.io/okhttp/)** — HTTP client for Android

## License

This project is open source. See the [LICENSE](LICENSE) file for details.
