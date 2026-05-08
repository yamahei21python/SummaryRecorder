import SwiftUI

// MARK: - Enums

enum TranscriptionMode: String, CaseIterable, Codable, Sendable {
    case groq
    case mlx
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
        didSet { UserDefaults.standard.set(transcriptionMode.rawValue, forKey: UDKey.transcriptionMode) }
    }
    @Published var summarizationMode: SummarizationMode {
        didSet { UserDefaults.standard.set(summarizationMode.rawValue, forKey: UDKey.summarizationMode) }
    }
    @Published var autoSummarize: Bool {
        didSet { UserDefaults.standard.set(autoSummarize, forKey: UDKey.autoSummarize) }
    }
    @Published var groqAPIKey: String = "" {
        didSet { UserDefaults.standard.set(groqAPIKey, forKey: UDKey.groqAPIKey) }
    }
    @Published var geminiAPIKey: String = "" {
        didSet { UserDefaults.standard.set(geminiAPIKey, forKey: UDKey.geminiAPIKey) }
    }
    @Published var summaryInstruction: String {
        didSet { UserDefaults.standard.set(summaryInstruction, forKey: UDKey.summaryInstruction) }
    }

    /// 固定の出力様式（ユーザー非表示）
    static let outputFormatSuffix = """
    以下のJSON形式で出力してください。
    {
        "title": "要約タイトル(20文字以内)",
        "summaryText": "要約本文"
    }
    ルール:
    - titleは内容を表す簡潔なタイトル
    - summaryTextは箇条書きで重要ポイントをまとめる
    - JSONのみ出力(マークダウンコードブロックなし)
    """

    /// ユーザー編集可能なデフォルト指示
    static let defaultSummaryInstruction = "以下のテキストを要約してください。"

    var whisperModelPath: String {
        AppPaths.modelsDirectory.appendingPathComponent(ModelFileName.whisper).path
    }

    var llamaModelPath: String {
        AppPaths.modelsDirectory.appendingPathComponent(ModelFileName.llama).path
    }

    /// モデルファイル実在確認
    var isWhisperModelDownloaded: Bool {
        FileManager.default.fileExists(atPath: whisperModelPath)
    }
    var isLLaMAModelDownloaded: Bool {
        FileManager.default.fileExists(atPath: llamaModelPath)
    }
    var areModelsDownloaded: Bool { isWhisperModelDownloaded && isLLaMAModelDownloaded }

    static let shared = AppConfig()

    private init() {
        let ud = UserDefaults.standard
        let tm = TranscriptionMode(rawValue: ud.string(forKey: UDKey.transcriptionMode) ?? "") ?? .mlx
        let sm = SummarizationMode(rawValue: ud.string(forKey: UDKey.summarizationMode) ?? "") ?? .local
        let auto = ud.object(forKey: UDKey.autoSummarize) as? Bool ?? true

        _transcriptionMode = Published(wrappedValue: tm)
        _summarizationMode = Published(wrappedValue: sm)
        _autoSummarize = Published(wrappedValue: auto)

        let savedInstruction = ud.string(forKey: UDKey.summaryInstruction) ?? Self.defaultSummaryInstruction
        _summaryInstruction = Published(wrappedValue: savedInstruction)

        // APIキー復元 (local.propertiesがなければUserDefaultsから)
        groqAPIKey = ud.string(forKey: UDKey.groqAPIKey) ?? ""
        geminiAPIKey = ud.string(forKey: UDKey.geminiAPIKey) ?? ""

        importFromLocalProperties()
    }

    /// local.propertiesからAPI Keyを読込
    private func importFromLocalProperties() {
        // 優先順: 1. Application Support 2. Bundle
        let urls: [URL?] = [
            AppPaths.localProperties,
            Bundle.main.url(forResource: "local", withExtension: "properties"),
        ]
        for case let url? in urls where FileManager.default.fileExists(atPath: url.path) {
            guard let content = try? String(contentsOf: url, encoding: .utf8) else { continue }
            NSLog("[AppConfig] importing keys from %@", url.path)

            let lines = content.components(separatedBy: .newlines)
            for line in lines {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.hasPrefix("groq.api.key=") {
                    groqAPIKey = String(trimmed.dropFirst("groq.api.key=".count))
                } else if trimmed.hasPrefix("gemini.api.key=") {
                    geminiAPIKey = String(trimmed.dropFirst("gemini.api.key=".count))
                }
            }
            break
        }
    }

    func makeTranscriptionService() -> TranscriptionService {
        switch transcriptionMode {
        case .groq:
            return GroqTranscriptionService(apiKey: groqAPIKey)
        case .mlx:
            let path = whisperModelPath
            return LocalTranscriptionService(modelPath: path)
        }
    }

    func makeSummarizationService() -> SummarizationService {
        let instruction = summaryInstruction.isEmpty ? Self.defaultSummaryInstruction : summaryInstruction
        let prompt = instruction + "\n\n" + Self.outputFormatSuffix
        switch summarizationMode {
        case .gemini:
            return GeminiSummarizationService(apiKey: geminiAPIKey, summaryPrompt: prompt)
        case .local:
            let path = llamaModelPath
            NSLog("[AppConfig] makeSummarizationService: llamaModelPath='%@'", path)
            return LocalSummarizationService(modelPath: path, systemPrompt: prompt)
        }
    }
}
