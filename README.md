# SummaryRecorder

AI-powered meeting recording & summarization for macOS.

- **Local Whisper** / **Groq API** for transcription
- **Local LLM (llama.cpp)** / **Gemini API** for summarization
- Metal GPU acceleration on Apple Silicon

## Requirements

- macOS 14.0 (Sonoma) or later
- Apple Silicon (M1+) recommended

## Install

### Homebrew (recommended)

```bash
brew tap yamahei21python/summary-recorder
brew install --cask summary-recorder
```

### DMG

Download from [Releases](https://github.com/yamahei21python/SummaryRecorder/releases).

## First Launch

Since the app is not signed with an Apple Developer certificate, macOS will show a security warning on first launch.

### Option A: Terminal

```bash
xattr -cr /Applications/SummaryRecorder.app
open /Applications/SummaryRecorder.app
```

### Option B: Finder

1. Open Finder → Applications
2. Right-click (Control-click) SummaryRecorder
3. Click "Open"
4. Click "Open" again in the dialog

After the first launch, the warning will not appear again.

## Setup

1. Launch the app
2. Open Settings
3. Enter your API keys:
   - **Groq API Key** — for cloud transcription ([get key](https://console.groq.com))
   - **Gemini API Key** — for cloud summarization ([get key](https://aistudio.google.com/apikey))
4. Local LLM / Whisper modes work without API keys

## Usage

1. Record or import audio
2. Transcribe (local Whisper or Groq API)
3. Summarize (local LLM or Gemini API)

## Build from Source

```bash
# Clone with submodules
git clone --recursive https://github.com/yamahei21python/SummaryRecorder.git
cd SummaryRecorder/PC

# Build dependencies
bash build-scripts/build-whisper.sh
bash build-scripts/build-llama.sh

# Generate Xcode project (requires XcodeGen)
xcodegen generate

# Build & run
xcodebuild -project SummaryRecorder.xcodeproj -scheme SummaryRecorder -configuration Debug build
```
