# Theia

A fast, lightweight voice assistant for Android designed around a floating bottom pill interface and low-latency response times.

Theia runs as a system-level Digital Assistant (`VoiceInteractionService`), triggering instantly from corner swipes or power-button gestures. It handles queries through a hybrid setup: low-latency cloud inference via Groq (`qwen/qwen3.8-27b`) when configured, backed by an embedded on-device engine (Needle 3 and Whistle) when offline.

---

## How It Works

1. **Activation**: Triggered via Android's default assistant gesture or home-screen launch. A custom AGSL edge shader traces the curved corners of your physical screen while listening.
2. **Audio Processing**: Captures 16 kHz mono audio using Android's `VOICE_RECOGNITION` audio source. An adaptive Voice Activity Detector (VAD) monitors ambient noise, records with a 500 ms pre-roll buffer, and normalizes audio peaks before inference. Short ambient noise or silence is dropped to prevent phantom transcriptions.
3. **Reasoning & Tool Calling**:
   - **Cloud**: Transcribes and reasons using Groq (`qwen/qwen3.8-27b`, ~40–80 ms latency). Automatically falls back to `openai/gpt-oss-20b` (low reasoning effort) if rate limits occur.
   - **Local**: When offline or unconfigured, falls back to the embedded C++ engine running the Whistle acoustic model (`whistle.cact`) and Needle 3 LLM (`needle3.cact`).
4. **Execution**: System-level tools execute directly on the phone (flashlight, volume, installed app launching, Play Store queries, settings panels, alarms, timers, and storage file search).
5. **Speech Synthesis**: Speaks responses aloud using Google Speech Services neural voices (Emma, Ava, Liam, Oliver, or offline Chloe). Spoken answers are strictly concise (1–2 sentences, under 35 words).
6. **Session-Scoped Memory**: Multi-turn conversation context is stored in a local SQLite database for the duration of the session and automatically discarded when Theia closes.

---

## Capabilities & Example Commands

| Category | Example Spoken Queries | Action |
| :--- | :--- | :--- |
| **App Launching** | *"Open Instagram"*, *"Launch Spotify"*, *"Open Twitter"* | Launches installed application via `PackageManager` with smart alias resolution. |
| **Play Store Fallback** | *"Download Twitter"*, *"Get Duolingo on Play Store"* | Opens Google Play Store search when an app is missing or requested. |
| **Flashlight** | *"Turn on flashlight"*, *"Lumos"*, *"Turn off torch"*, *"Nox"* | Toggles phone camera flash via `CameraManager`. |
| **Volume Control** | *"Turn the volume up"*, *"Set volume to 50%"*, *"Mute media"* | Adjusts music audio stream via `AudioManager`. |
| **System Settings** | *"Open Wi-Fi settings"*, *"Bluetooth settings"*, *"Display settings"* | Deep-links directly to specific Android system settings panels. |
| **Timers & Alarms** | *"Set a timer for 10 minutes"*, *"Alarm at 7:30 AM"* | Dispatches system intents to the default clock application. |
| **File Search** | *"Search report.pdf"*, *"Find invoice.docx"* | Traverses public external storage and opens matches using `FileProvider`. |
| **Device & Time Context** | *"What time is it?"*, *"What's my battery level?"*, *"What's today's date?"* | Pulls current system time, battery state, and device telemetry. |
| **General Knowledge** | *"Why is the sky blue?"*, *"Explain relativity simply"* | Generates a punchy, spoken-friendly summary in under 35 words. |

---

## Architecture

```
User Voice / Tap
       │
       ▼
Recorder.kt (16 kHz PCM + VAD + Pre-roll Buffer + Peak Gate)
       │
       ▼
Screen.kt (Orchestrator) ─── AGSL Shimmer & Visualizer
       │
       ├── Cloud Path (Groq API Key configured):
       │     ├── STT: Whisper-Large-V3
       │     ├── LLM: qwen/qwen3.8-27b (Fallback: gpt-oss-20b)
       │     └── SQLite Session Memory: theia.db
       │
       └── Offline Fallback (Local C++ JNI):
             ├── STT: whistle.cact (Whistle Acoustic Model)
             └── LLM: needle3.cact (Needle 3 Language Model)
       │
       ├── Tool Calls ────────► Actions.kt (Torch, Volume, Apps, Settings, Alarms, Files)
       │
       ▼
Speaker.kt (Google Speech Services TTS) ───► Spoken Audio Output
```

---

## Project Structure

```
theia/
├── app/
│   ├── src/main/
│   │   ├── java/in/iambhvsh/theia/
│   │   │   ├── ai/
│   │   │   │   ├── Engine.kt         # JNI bridge to embedded Cactus Needle C++ engine
│   │   │   │   └── Groq.kt           # Groq API client, model routing, system context, tool schemas
│   │   │   ├── audio/
│   │   │   │   ├── Recorder.kt       # AudioRecord capture, dynamic noise floor, VAD, gain normalizer
│   │   │   │   └── Speaker.kt        # Google Speech Services TTS, voice presets, fallback handling
│   │   │   ├── components/
│   │   │   │   ├── Shimmer.kt        # AGSL GPU runtime shader for curved edge glow
│   │   │   │   └── Visualizer.kt     # Multi-mode signature audio waveform & sparkle view
│   │   │   ├── data/
│   │   │   │   └── Database.kt       # SQLite session-scoped conversational history store
│   │   │   ├── screens/
│   │   │   │   └── Screen.kt         # Minimalist bottom pill, response card, settings view, input coordinator
│   │   │   ├── service/
│   │   │   │   ├── Assistant.kt      # Android VoiceInteractionService entry point
│   │   │   │   ├── Recognizer.kt     # Android SpeechRecognizer service placeholder
│   │   │   │   └── Session.kt        # VoiceInteractionSession & gesture launch coordinator
│   │   │   └── tools/
│   │   │       ├── Actions.kt        # Hardware controls (torch, volume, apps, settings, alarms, files)
│   │   │       └── Parser.kt         # Tool-call parser and arguments normalizer
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt        # NDK build configuration (16 KB page-size aligned)
│   │   │   ├── jni.cpp               # C++ JNI bindings for libneedle
│   │   │   └── engine/               # Needle headers and libneedle.a static library
│   │   ├── assets/
│   │   │   ├── needle3.cact          # Embedded Needle 3 LLM weights (34 MB)
│   │   │   └── whistle.cact          # Embedded Whistle STT acoustic model (16 MB)
│   │   └── res/                      # Android drawables, styles, and XML configs
├── scripts/
│   ├── setup.sh                      # Unified cross-platform engine asset downloader
│   ├── setup_macos.sh                # macOS setup script
│   ├── setup_linux.sh                # Linux setup script
│   ├── setup_windows.ps1             # Windows PowerShell setup script
│   └── setup_windows.bat             # Windows batch setup script
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## Getting Started

### Prerequisites

- **Java Development Kit**: JDK 17
- **Android SDK**: API level 35 (Build-Tools 34+)
- **Android NDK**: Version `27.0.12077973`
- **Device**: ARM64 Android device running Android 11+ (API 30+) with USB debugging enabled
- **Python**: 3.9+ (used by the setup scripts to fetch binary assets)

---

### 1. Clone the Repository

```bash
git clone https://github.com/iambhvsh/theia.git
cd theia
```

---

### 2. Fetch Engine Libraries & Model Weights

Run the setup script for your platform to download the precompiled `libneedle.a` static library and model files (`whistle.cact`, `needle3.cact`):

**macOS / Linux:**
```bash
./scripts/setup.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\setup_windows.ps1
```

**Windows (Command Prompt):**
```cmd
.\scripts\setup_windows.bat
```

---

### 3. Build & Install

Connect your Android device via USB and run:

```bash
./gradlew installDebug
```

Or open the project in Android Studio and deploy to your connected device.

---

### 4. Configuration (Groq Cloud Inference)

To enable cloud reasoning with Qwen 2.5:

1. Launch Theia.
2. Long-press the microphone icon on the floating pill, or tap the **⋮** menu on any response card and select **Settings**.
3. Enter your Groq API key (available at [console.groq.com](https://console.groq.com/keys)).
4. Tap **Save Settings**.

You can also switch the assistant voice preset (Emma, Ava, Liam, Oliver, Chloe) or customize the default model name from this screen.

---

### 5. Set Theia as Default Digital Assistant (Optional)

To trigger Theia via your device's native assistant shortcut (corner swipe, home button long-press, or power-button gesture):

1. Open Android **Settings**.
2. Navigate to **Apps** > **Default apps** > **Digital assistant app**.
3. Tap **Default digital assistant app** and select **Theia**.

---

## Technical Details

- **AGSL Screen Border Shader**: On Android 13+ (API 33+), Theia renders its screen perimeter glow using an AGSL RuntimeShader. It queries `WindowInsets.getRoundedCorner` to pull the physical glass curvature of the phone's corners and runs an Inigo Quilez 2D rounded-box signed distance field (SDF) to keep the gradient bound to the device's bezels.
- **Microphone VAD & Silence Rejection**: The audio pipeline continuously samples ambient noise floor. Recordings with peak amplitude below `0.035` are discarded before reaching the transcription engine, cutting out common Whisper background hallucinations.
- **Android 15 16 KB Page Size**: The native library links with `-Wl,-z,max-page-size=16384` to ensure forward compatibility with 16 KB memory page kernels on upcoming Android platforms.
- **Transient Memory**: Rather than accumulating indefinite chat logs, conversation history is scoped strictly to the current session (`sessionId`) and purged from SQLite on activity dismiss.

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
