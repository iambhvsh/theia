# Theia 🦉

> **Private, local-first, on-device AI voice assistant for Android.**

Theia is an open-source, completely offline voice assistant built from the ground up for privacy and speed. Everything—speech recognition, language understanding, tool routing, and voice synthesis—runs 100% locally on your phone's processor. No cloud APIs, no internet connection required, and zero telemetry.

---

## ✨ Features

- 🔒 **100% Private & Offline:** Your voice and queries never leave your device. All neural models run locally on-device.
- ⚡ **Real-Time Edge Inference:** Native C++ engine with SIMD optimizations and JNI bindings delivers rapid responses directly on CPU/NPU.
- 🎙️ **On-Device Speech-to-Text (STT):** Powered by the **Whistle** acoustic model (`whistle.cact`) with adaptive Voice Activity Detection (VAD), pre-roll buffer, and automatic audio normalization.
- 🧠 **Local LLM & Tool Calling:** Powered by **Needle 3** (`needle3.cact`), understanding user intent and executing actions with structured function calls.
- 🗣️ **Natural Voice Synthesis (TTS):** Google Speech Services studio-quality neural voice presets (Warm, Crisp, Expressive, British, Deep) with instant (~90ms) turnaround and zero breathing/sighing artifacts.
- ⚡ **Real-Time Conversations & Context:** Groq ultra-low-latency LPU inference (`qwen/qwen3.8-27b`) with native SQLite conversational memory.
- 📱 **Android System Integration:**
  - Registered as a native Android **Digital Assistant** (`VoiceInteractionService`).
  - Launches via Android's default assistant gesture (corner swipe / long-press power / home).
  - Lock-screen capable UI (`showWhenLocked` and `turnScreenOn`).
  - Native tools: launch apps, set timers, schedule alarms, and search on-device files.
  - Conversational fallback for greetings and direct queries.

---

## 🏛️ Architecture

```
                                  ┌────────────────────────┐
                                  │      User Voice        │
                                  └───────────┬────────────┘
                                              │ AudioRecord (VOICE_RECOGNITION, 16 kHz)
                                              ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │  Theia Core                                                                            │
 │                                                                                        │
 │  ┌───────────────────────┐      PCM Float     ┌─────────────────────────────────────┐  │
 │  │      Recorder.kt      ├───────────────────►│       Whistle STT (JNI / C++)       │  │
 │  │  Adaptive VAD + Gain  │                    │            whistle.cact             │  │
 │  └───────────────────────┘                    └──────────────────┬──────────────────┘  │
 │                                                                  │ Transcribed Text    │
 │                                                                  ▼                     │
 │  ┌───────────────────────┐   Cleaned Prompt   ┌─────────────────────────────────────┐  │
 │  │       Screen.kt       ├───────────────────►│      Needle 3 LLM (JNI / C++)       │  │
 │  │  UI & Orchestration   │                    │            needle3.cact             │  │
 │  └───────────┬───────────┘                    └──────────────────┬──────────────────┘  │
 │              │                                                   │ Function Call JSON  │
 │              │                                                   ▼                     │
 │              │                                ┌─────────────────────────────────────┐  │
 │              │◄───────────────────────────────┤              Actions.kt             │  │
 │              │          Status & Text         │    Alarms, Timers, Apps, Files      │  │
 │              ▼                                └─────────────────────────────────────┘  │
 │  ┌───────────────────────┐                                                             │
 │  │       Speaker.kt      │                                                             │
 │  │ Google Neural Presets ├───────────────────► Audio Output (Speaker / Headphones)     │
 │  └───────────────────────┘                                                             │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📁 Repository Structure

```
theia/
├── app/
│   ├── src/main/
│   │   ├── java/in/iambhvsh/theia/
│   │   │   ├── Actions.kt        # Local phone actions (alarm, timer, apps, files)
│   │   │   ├── Assistant.kt      # Android VoiceInteractionService entrypoint
│   │   │   ├── Database.kt       # Native SQLite session & conversational memory
│   │   │   ├── Engine.kt         # JNI bridge to Cactus Needle native engine
│   │   │   ├── Groq.kt           # Real-time Groq cloud LLM client & connection manager
│   │   │   ├── Parser.kt         # Tolerant function call & tool parser
│   │   │   ├── Recognizer.kt     # Speech recognition service definition
│   │   │   ├── Recorder.kt       # 16 kHz audio capture, VAD & normalization
│   │   │   ├── Screen.kt         # Primary UI, assistant lifecycle & voice selector
│   │   │   ├── Session.kt        # VoiceInteractionSession & gesture handling
│   │   │   └── Speaker.kt        # Google Speech Services neural voice presets
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt    # NDK CMake build configuration
│   │   │   ├── jni.cpp           # Native C++ bindings for libneedle
│   │   │   └── engine/           # Engine headers & libneedle.a (fetched via setup)
│   │   └── assets/
│   │       ├── needle3.cact      # Needle 3 LLM weights (fetched via setup)
│   │       └── whistle.cact      # Whistle STT model (fetched via setup)
├── scripts/
│   ├── setup.sh                  # Universal cross-platform setup entrypoint
│   ├── setup_macos.sh            # Automated setup for macOS
│   ├── setup_linux.sh            # Automated setup for Linux
│   ├── setup_windows.ps1         # Automated setup for Windows PowerShell
│   └── setup_windows.bat         # Automated setup for Windows Command Prompt
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Getting Started

### Prerequisites

1. **Java:** JDK 17
2. **Android SDK & NDK:** Android SDK (API 35, Build-Tools 34+) and NDK `27.0.12077973`
3. **Python:** Python 3.9+ (used by the setup script to download the models)
4. **Android Device:** ARM64 Android device running Android 11+ (API 30+) with USB Debugging enabled

---

### Step 1: Clone the Repository

```bash
git clone https://github.com/iambhvsh/theia.git
cd theia
```

---

### Step 2: Fetch Engine & Model Weights

Run the setup script for your platform to download the Cactus Needle C++ engine library (`libneedle.a`), `needle.h`, and the model weights (`whistle.cact` and `needle3.cact`):

**macOS / Linux:**
```bash
./scripts/setup.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\setup_windows.ps1
```

**Windows (CMD / Double-Click):**
```cmd
.\scripts\setup_windows.bat
```

> *Voice synthesis uses native Google Speech Services neural presets with zero setup.*

---

### Step 3: Build & Install

Connect your Android phone via USB and run:

```bash
./gradlew installDebug
```

Or open the project in **Android Studio** and click **Run**.

---

### Step 4: Set as Default Assistant (Optional)

To trigger Theia via your phone's default assistant gesture:

1. Open your phone's **Settings**.
2. Go to **Apps** > **Default apps** > **Digital assistant app**.
3. Select **Theia**.
4. You can now invoke Theia using your phone's power-button hold, corner swipe, or gesture navigation!

---

## 🛠️ Built-in Capabilities

| Action | Example Voice Command |
| :--- | :--- |
| **Open App** | *"Open YouTube"*, *"Open Settings"*, *"Open Camera"* |
| **Set Timer** | *"Set a timer for 10 minutes"*, *"Timer 5 minutes pizza"* |
| **Set Alarm** | *"Set an alarm for 7:30 AM"*, *"Wake me up at 8 AM"* |
| **Search Files** | *"Search report.pdf"*, *"Find catalog.apk"* |
| **Conversational** | *"What is your name?"*, *"Who are you?"*, *"How are you?"* |

---

## 🛡️ Privacy Principles

- **No Network Permissions for Inference:** The native inference engine and speech models operate strictly on local file buffers.
- **No Analytics / Telemetry:** No user data, transcripts, or hardware identifiers are collected or transmitted.
- **Local Storage Access:** File search strictly indexes local public storage when explicitly permitted by the user (`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`).

---

## 📜 Acknowledgments & Open Source Libraries

- [Cactus Needle](https://cactuscompute.com) &mdash; Native on-device execution engine and models (`whistle` STT and `needle3` LLM).
- [Groq](https://groq.com) &mdash; Fast cloud LPU inference (`qwen/qwen3.8-27b`).
- [Google Speech Services](https://play.google.com/store/apps/details?id=com.google.android.tts) &mdash; Studio-quality neural voice synthesis.

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
