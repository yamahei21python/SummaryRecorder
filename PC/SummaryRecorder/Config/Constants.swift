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
        let dir = appSupportDirectory.appendingPathComponent("recordings", isDirectory: true)
        return dir
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

    private static var appSupportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
    }
}

// MARK: - Model File Names

enum ModelFileName {
    static let whisper = "ggml-medium.bin"
    static let llama = "google_gemma-4-E2B-it-Q4_K_M.gguf"
}

// MARK: - API Endpoints

enum APIEndpoint {
    static let groqTranscription = "https://api.groq.com/openai/v1/audio/transcriptions"
    static let geminiSummarization = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent"
    static let whisperDownload = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin"
    static let llamaDownload = "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_K_M.gguf"
}

// MARK: - Groq Model

enum GroqModel {
    static let transcription = "whisper-large-v3-turbo"
}

// MARK: - Shared Formatters

enum AppFormatters {
    static func fallbackTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy/MM/dd HH:mm"
        return formatter.string(from: Date())
    }
}
