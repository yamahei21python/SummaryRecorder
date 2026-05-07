import SwiftUI

// MARK: - Enums

enum TranscriptionMode: String, CaseIterable, Codable, Sendable {
    case mlx
    case groq
}

extension TranscriptionMode {
    var displayName: String {
        switch self {
        case .groq: "Groq"
        case .mlx: "Local"
        }
    }
}

enum SummarizationMode: String, CaseIterable, Codable, Sendable {
    case gemini
    case local
}

extension SummarizationMode {
    var displayName: String {
        switch self {
        case .gemini: "Gemini"
        case .local: "Local"
        }
    }
}

// MARK: - AppConfig

@MainActor
final class AppConfig: ObservableObject {
    @Published var transcriptionMode: TranscriptionMode {
        didSet { UserDefaults.standard.set(transcriptionMode.rawValue, forKey: "transcription_mode") }
    }
    @Published var summarizationMode: SummarizationMode {
        didSet { UserDefaults.standard.set(summarizationMode.rawValue, forKey: "summarization_mode") }
    }
    @Published var autoSummarize: Bool {
        didSet { UserDefaults.standard.set(autoSummarize, forKey: "auto_summarize") }
    }
    @Published var groqAPIKey: String = ""
    @Published var geminiAPIKey: String = ""
    var whisperModelPath: String {
        get { UserDefaults.standard.string(forKey: "whisper_model_path") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "whisper_model_path") }
    }
    var llamaModelPath: String {
        get {
            if let path = UserDefaults.standard.string(forKey: "llama_model_path"), !path.isEmpty {
                return path
            }
            // フォールバック: Application Support/models/ を自動検索
            let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
                .appendingPathComponent("models", isDirectory: true)
            let autoPath = dir.appendingPathComponent("google_gemma-4-E2B-it-Q4_K_M.gguf")
            if FileManager.default.fileExists(atPath: autoPath.path) {
                return autoPath.path
            }
            return ""
        }
        set { UserDefaults.standard.set(newValue, forKey: "llama_model_path") }
    }

    /// モデルファイル実在確認 (MLXは不要、LLaMAのみ)
    var isLLaMAModelDownloaded: Bool {
        !llamaModelPath.isEmpty && FileManager.default.fileExists(atPath: llamaModelPath)
    }
    var areModelsDownloaded: Bool { isLLaMAModelDownloaded }

    static let shared = AppConfig()
    private let keychain = KeychainManager()

    private init() {
        let ud = UserDefaults.standard
        let tm = TranscriptionMode(rawValue: ud.string(forKey: "transcription_mode") ?? "") ?? .mlx
        let sm = SummarizationMode(rawValue: ud.string(forKey: "summarization_mode") ?? "") ?? .gemini
        let auto = ud.object(forKey: "auto_summarize") as? Bool ?? true

        _transcriptionMode = Published(wrappedValue: tm)
        _summarizationMode = Published(wrappedValue: sm)
        _autoSummarize = Published(wrappedValue: auto)

        // 1. local.properties → Keychainに自動保存
        importFromLocalProperties()
        // 2. Keychainから読込（1で保存した値 or 以前の保存値）
        loadAPIKeysFromKeychain()
    }

    /// workspace rootのlocal.propertiesからAPI Keyを読んでKeychain保存
    private func importFromLocalProperties() {
        guard let url = localPropertiesURL(),
              let content = try? String(contentsOf: url, encoding: .utf8)
        else { return }

        let lines = content.components(separatedBy: .newlines)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("groq.api.key=") {
                let key = String(trimmed.dropFirst("groq.api.key=".count))
                if !key.isEmpty { keychain.set(key: "groq_api_key", value: key) }
            } else if trimmed.hasPrefix("gemini.api.key=") {
                let key = String(trimmed.dropFirst("gemini.api.key=".count))
                if !key.isEmpty { keychain.set(key: "gemini_api_key", value: key) }
            }
        }
    }

    private func localPropertiesURL() -> URL? {
        // #file → .../SummaryRecorder/PC/SummaryRecorder/Config/AppConfig.swift
        let url = URL(fileURLWithPath: #file)
        // 4 levels up → workspace root (/Users/kohei/Myproject/SummaryRecorder/)
        let workspaceRoot = url
            .deletingLastPathComponent() // Config/
            .deletingLastPathComponent() // SummaryRecorder/
            .deletingLastPathComponent() // PC/
            .deletingLastPathComponent() // SummaryRecorder/ (workspace root)
        let props = workspaceRoot.appendingPathComponent("local.properties")
        return FileManager.default.fileExists(atPath: props.path) ? props : nil
    }

    /// KeychainからAPI Keyを読み込む
    func loadAPIKeysFromKeychain() {
        groqAPIKey = keychain.get(key: "groq_api_key") ?? ""
        geminiAPIKey = keychain.get(key: "gemini_api_key") ?? ""
    }

    /// 現在のAPI KeyをKeychainに保存
    func saveAPIKeys() {
        keychain.set(key: "groq_api_key", value: groqAPIKey)
        keychain.set(key: "gemini_api_key", value: geminiAPIKey)
    }

    func makeTranscriptionService() -> TranscriptionService {
        switch transcriptionMode {
        case .groq:
            return GroqTranscriptionService(apiKey: groqAPIKey)
        case .mlx:
            return MLXTranscriptionService()
        }
    }

    func makeSummarizationService() -> SummarizationService {
        switch summarizationMode {
        case .gemini:
            return GeminiSummarizationService(apiKey: geminiAPIKey)
        case .local:
            let path = llamaModelPath
            NSLog("[AppConfig] makeSummarizationService: llamaModelPath='%@' isEmpty=%d", path, path.isEmpty)
            if path.isEmpty {
                return LocalSummarizationService()
            }
            return LocalSummarizationService(modelPath: path)
        }
    }
}
