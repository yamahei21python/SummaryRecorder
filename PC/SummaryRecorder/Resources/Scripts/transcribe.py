#!/usr/bin/env python3
"""Lightning Whisper MLX transcription script. Called by MLXTranscriptionService."""

import sys, json, os

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: transcribe.py <wav_path> [--model model_name]"}))
        sys.exit(1)

    wav_path = sys.argv[1]
    model_name = "small"

    if "--model" in sys.argv:
        idx = sys.argv.index("--model")
        if idx + 1 < len(sys.argv):
            model_name = sys.argv[idx + 1]

    if not os.path.isfile(wav_path):
        print(json.dumps({"error": f"File not found: {wav_path}"}))
        sys.exit(1)

    try:
        from lightning_whisper_mlx import LightningWhisperMLX
    except ImportError:
        print(json.dumps({"error": "lightning-whisper-mlx not installed. Run: pip install lightning-whisper-mlx"}))
        sys.exit(1)

    try:
        whisper = LightningWhisperMLX(model=model_name, batch_size=12)
        result = whisper.transcribe(wav_path)
        text = result.get("text", "").strip()
        print(json.dumps({"text": text}))
    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)

if __name__ == "__main__":
    main()
