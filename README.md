# Theia

A voice assistant for Android that registers as the system default digital assistant. It triggers via corner swipe, power button hold, or app icon, transcribes speech on-device, queries a cloud LLM, and speaks the response aloud through a floating bottom-sheet interface.

## What it does

Theia replaces Google Assistant on your phone. You trigger it the same way (corner swipe, power button hold), and it appears as a dark floating pill at the bottom of your screen. Speak a command or question, and it:

1. Transcribes your voice locally using an embedded Whistle acoustic model (`whistle.cact`, 16 MB)
2. Sends the transcript to [Groq](https://groq.com) for reasoning and tool-call decisions (model: `qwen/qwen3.8-27b`)
3. Executes device actions directly — open apps, toggle flashlight, set alarms, adjust volume, search files
4. Speaks a short answer aloud using Google Speech Services TTS

If Groq is unreachable or no API key is configured, Theia falls back to an embedded Needle 3 language model (`needle3.cact`, 34 MB) for both reasoning and tool calls, fully offline.

Conversations are session-scoped: context is kept in a local SQLite database while Theia is open, and deleted when you close it.

## Features

- **System-level assistant** — registers as `VoiceInteractionService`, triggered by Android's native assistant gesture
- **On-device transcription** — speech is always transcribed locally via Whistle; audio never leaves the phone
- **Cloud reasoning with local fallback** — Groq API for fast inference, Needle 3 on-device when offline
- **Tool execution** — launches apps, toggles flashlight, controls volume, opens settings panels, sets timers/alarms, searches local files, opens Play Store and URLs, searches Google
- **Voice output** — responds aloud through Google Speech Services with selectable voices (Emma, Ava, Liam, Oliver, Chloe)
- **Screen-edge glow** — AGSL GPU shader that briefly lights up the screen perimeter on activation, using the device's actual hardware corner radii; auto-hides after 2 seconds
- **Silence and phantom phrase filtering** — rejects ambient noise below a dynamic RMS threshold and drops common acoustic model hallucinations (e.g. "thank you", "please subscribe") on short recordings
- **No heavy dependencies** — the only Gradle dependency is `androidx.core:core-ktx`. Everything else is Android platform APIs and the native Needle engine

## Getting started

### Prerequisites

- JDK 17
- Android SDK with API level 35, Build-Tools 34+, NDK `27.0.12077973`
- Python 3.9+ (the setup script uses it to install the `cactus-needle` Python package, which downloads the native library and model files)
- An ARM64 Android device running Android 11 or later, with USB debugging enabled

### Clone and set up

```bash
git clone https://github.com/iambhvsh/theia.git
cd theia
```

Run the setup script for your platform. It installs the `cactus-needle` pip package in a local virtualenv and uses its `needle` CLI to download `libneedle.a`, `whistle.cact`, and `needle3.cact` into the right directories:

```bash
# macOS or Linux
./scripts/setup.sh

# Windows (PowerShell)
.\scripts\setup_windows.ps1
```

### Build and install

```bash
./gradlew installDebug
```

Or open the project in Android Studio and deploy to a connected device.

### Configure cloud inference

Theia works out of the box with the embedded on-device models. For faster, higher-quality responses:

1. Open Theia on your phone
2. Long-press the microphone icon, or tap **⋮** on a response card and select **Settings**
3. Enter a Groq API key (free at [console.groq.com/keys](https://console.groq.com/keys))
4. Tap **Save Settings**

You can also change the LLM model name and select a different TTS voice from this screen.

### Set as default assistant (optional)

Go to Android **Settings → Apps → Default apps → Digital assistant app** and select **Theia**.

## Project structure

```
app/src/main/
├── java/in/iambhvsh/theia/
│   ├── ai/
│   │   ├── Engine.kt          # JNI bridge to the native Needle engine
│   │   └── Groq.kt            # Groq chat completions client, tool schemas, system prompt
│   ├── audio/
│   │   ├── Recorder.kt        # 16 kHz PCM capture, adaptive VAD, pre-roll buffer, peak normalization
│   │   └── Speaker.kt         # Google Speech Services TTS wrapper, voice presets, offline fallback
│   ├── components/
│   │   ├── Shimmer.kt         # AGSL RuntimeShader — rounded-box SDF edge glow, voice-reactive
│   │   └── Visualizer.kt      # 3-bar waveform (listening/speaking) and rotating sparkle (thinking)
│   ├── data/
│   │   └── Database.kt        # SQLite session-scoped message store
│   ├── screens/
│   │   └── Screen.kt          # Main UI — floating pill, response card, settings page, query orchestration
│   ├── service/
│   │   ├── Assistant.kt       # VoiceInteractionService entry point
│   │   ├── Recognizer.kt      # Placeholder RecognitionService (required by voice_interaction.xml)
│   │   └── Session.kt         # VoiceInteractionSession — wake gesture handler, launches Screen
│   └── tools/
│       ├── Actions.kt         # Device action executors (apps, torch, volume, settings, alarms, files, URLs)
│       └── Parser.kt          # Tool-call JSON parser
├── cpp/
│   ├── jni.cpp                # C++ JNI bindings for libneedle
│   ├── CMakeLists.txt         # NDK build config (16 KB page-size aligned for Android 15+)
│   └── engine/                # needle.h and libneedle.a (downloaded by setup script)
├── assets/
│   ├── needle3.cact           # Needle 3 LLM weights (34 MB)
│   └── whistle.cact           # Whistle STT acoustic model (16 MB)
└── res/                       # Drawables, styles, XML configs
```

## Tools

These are the device actions Theia can execute, either through Groq tool-calling or through the local Needle engine's function-call output:

| Tool | What it does |
|---|---|
| `open_app` | Launches an installed app by name, with alias matching (e.g., "Twitter" matches the X app) |
| `open_play_store` | Opens Google Play Store search for an app |
| `open_url` | Opens a URL in the browser |
| `search_web` | Opens a Google search |
| `control_flashlight` | Toggles the camera flash on/off |
| `adjust_volume` | Raises, lowers, sets, or mutes media volume |
| `open_settings` | Deep-links to a system settings panel (Wi-Fi, Bluetooth, Display, Sound, Apps, Battery) |
| `set_timer` | Creates a countdown timer via the system clock app |
| `set_alarm` | Sets a clock alarm at a specific time |
| `search_files` | Traverses external storage, matches filenames, and opens results via FileProvider |

When Groq is unavailable, the on-device Needle engine supports a subset: `open_app`, `set_timer`, `set_alarm`, and `search_files`.

## How it works

```
Voice Input
    │
    ▼
Recorder.kt ── 16 kHz mono PCM, adaptive noise floor, VAD, 500 ms pre-roll, peak gate
    │
    ▼
Engine.kt (JNI) ── whistle.cact transcription (always on-device)
    │
    ▼
Screen.kt (orchestrator)
    │
    ├─ Groq API key present? ──► Groq.kt ── qwen/qwen3.8-27b ── tool calls + text response
    │                                        (falls back to openai/gpt-oss-20b on 429)
    │
    └─ No key / offline? ──► Engine.kt (JNI) ── needle3.cact ── tool calls + text response
    │
    ▼
Actions.kt ── executes tool calls on the device
    │
    ▼
Speaker.kt ── Google Speech Services TTS ── spoken output
```

Transcription always happens on-device through the Whistle model, regardless of whether a Groq key is configured. Only the reasoning step (LLM chat completion) goes to Groq.

## Limitations

- ARM64 only. No x86 or 32-bit ARM support — the native Needle engine is compiled for `arm64-v8a` exclusively.
- Requires Android 11+ (API 30). The AGSL edge shader requires Android 13+ (API 33); on older versions it falls back to a simpler gradient stroke.
- The on-device Needle 3 model is small (34 MB). Its responses are basic compared to the Groq-hosted Qwen model. For anything beyond simple commands and short answers, you need the Groq API key configured.
- No wake word. Theia activates only through Android's assistant gesture or by tapping its launcher icon.
- English only for voice input and output.

## License

[Apache License 2.0](LICENSE)
