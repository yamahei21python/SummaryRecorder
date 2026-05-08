import Foundation

// MARK: - UserDefaults Keys
enum UDKey {
    static let transcriptionMode = "transcription_mode"
    static let summarizationMode = "summarization_mode"
    static let autoSummarize = "auto_summarize"
    static let groqAPIKey = "groq_api_key"
    static let geminiAPIKey = "gemini_api_key"
    static let summaryInstruction = "summary_instruction"
    static let modelBookmark = "model_bookmark_data"
}

// MARK: - App Paths

enum AppPaths {
    static var recordingsDirectory: URL {
        appSupportDirectory.appendingPathComponent("recordings", isDirectory: true)
    }

    static var modelsDirectory: URL {
        appSupportDirectory.appendingPathComponent("models", isDirectory: true)
    }

    static var localProperties: URL {
        appSupportDirectory.appendingPathComponent("local.properties")
    }

    static var whisperModel: URL {
        modelsDirectory.appendingPathComponent("whisper-model-file")
    }

    static var llamaModel: URL {
        modelsDirectory.appendingPathComponent("llama-model-file")
    }

    private static let appSupportDirectory: URL = {
        guard let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            fatalError("Application Support directory not found — this should never happen on macOS")
        }
        return url
    }()
}

// MARK: - Model File Names

enum ModelFileName {
    static let whisper = "ggml-medium.bin"
    static let llama = "google_gemma-4-E2B-it-Q4_K_M.gguf"
}

// MARK: - API Endpoints (URL type — centralized force unwrap for compile-time string literals)

enum APIEndpoint {
    static let groqTranscription = URL(string: "https://api.groq.com/openai/v1/audio/transcriptions")!
    static let geminiSummarization = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent")!
    static let whisperDownload = URL(string: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin")!
    static let llamaDownload = URL(string: "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_K_M.gguf")!
}

// MARK: - Groq Model

enum GroqModel {
    static let transcription = "whisper-large-v3-turbo"
}

// MARK: - WAV Constants

enum WavConstants {
    static let headerSize = 44
    static let sampleRate: UInt32 = 16000
    static let channels: UInt16 = 1
    static let bitsPerSample: UInt16 = 16
    static let byteRate: UInt32 = 32000  // sampleRate * channels * bitsPerSample / 8
    static let blockAlign: UInt16 = 2    // channels * bitsPerSample / 8
    static let subchunk1Size: UInt32 = 16 // PCM
    static let audioFormat: UInt16 = 1   // PCM
}

// MARK: - App Limits

enum AppLimits {
    static let titleMaxLength = 20
    static let maxGroqChunkSize = 25 * 1024 * 1024
}

// MARK: - Shared Formatters

enum AppFormatters {
    static func fallbackTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy/MM/dd HH:mm"
        return formatter.string(from: Date())
    }
}
