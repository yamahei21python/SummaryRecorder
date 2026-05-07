import Foundation

final class LocalSummarizationService: SummarizationService {
    private let llamaBridge: LLamaBridgeProtocol

    init(llamaBridge: LLamaBridgeProtocol = LLamaBridgeSwift()) {
        self.llamaBridge = llamaBridge
    }

    // Convenience init with model path for production use
    convenience init(modelPath: String) {
        let bridge = LLamaBridgeSwift(modelPath: modelPath)
        self.init(llamaBridge: bridge)
    }

    func summarize(text: String, cancelState: CancelState) async throws -> SummaryOutput {
        cancelState.markCallbackReached()

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        let json = try await llamaBridge.generate(prompt: text, cancelState: cancelState)

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        return try parseOutput(json: json)
    }

    // MARK: - Private

    private func parseOutput(json: String) throws -> SummaryOutput {
        // Step 1: Extract JSON from markdown code blocks
        var raw = json
            .replacingOccurrences(of: "```json", with: "")
            .replacingOccurrences(of: "```", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // Step 2: Extract JSON object via regex (find { ... } with title/summaryText)
        if let extracted = extractJSON(from: raw) {
            raw = extracted
        }

        // Step 3: Try direct parse
        if let data = raw.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let title = parsed["title"] as? String ?? ""
            let summaryText = parsed["summaryText"] as? String ?? ""
            if !title.isEmpty || !summaryText.isEmpty {
                return SummaryOutput(
                    title: title.isEmpty ? formatFallbackTitle() : title,
                    summaryText: summaryText.isEmpty ? raw : summaryText
                )
            }
        }

        // Step 4: Try to repair incomplete JSON
        if let repaired = repairAndParse(raw) {
            return repaired
        }

        // Step 5: Fallback — raw text as summaryText
        return SummaryOutput(title: formatFallbackTitle(), summaryText: json)
    }

    // MARK: - JSON Extraction Helpers

    /// Find first JSON object containing "title" from raw text
    private func extractJSON(from text: String) -> String? {
        // Try to find balanced braces
        guard let start = text.firstIndex(of: "{") else { return nil }
        var depth = 0
        var end = start
        for i in text[start...] {
            if i == "{" { depth += 1 }
            if i == "}" { depth -= 1 }
            if depth == 0 { break }
            end = text.index(after: end)
        }
        let candidate = String(text[start...end])
        return candidate.contains("\"title\"") ? candidate : nil
    }

    /// Repair incomplete JSON and parse
    private func repairAndParse(_ text: String) -> SummaryOutput? {
        // Try adding closing braces/quotes
        var repaired = text

        // Count unbalanced braces
        let openBraces = repaired.filter { $0 == "{" }.count
        let closeBraces = repaired.filter { $0 == "}" }.count
        if openBraces > closeBraces {
            repaired += String(repeating: "}", count: openBraces - closeBraces)
        }

        // Count unbalanced quotes (simple heuristic)
        let quoteCount = repaired.filter { $0 == "\"" }.count
        if quoteCount % 2 != 0 {
            repaired += "\""
        }

        // Try parse again
        guard let data = repaired.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        let title = parsed["title"] as? String ?? ""
        let summaryText = parsed["summaryText"] as? String ?? ""
        guard !title.isEmpty || !summaryText.isEmpty else { return nil }

        return SummaryOutput(
            title: title.isEmpty ? formatFallbackTitle() : title,
            summaryText: summaryText.isEmpty ? text : summaryText
        )
    }

    private func formatFallbackTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy/MM/dd HH:mm"
        return formatter.string(from: Date())
    }
}

// MARK: - Protocol for testability

protocol LLamaBridgeProtocol: Sendable {
    func generate(prompt: String, cancelState: CancelState) async throws -> String
}

// MARK: - Swift Bridge (Phase 5: connects to LLamaBridgeObjC)

final class LLamaBridgeSwift: LLamaBridgeProtocol, @unchecked Sendable {
    private let objcBridge: LLamaBridgeObjC
    private let systemPrompt = """
    指示に従い、以下のテキストを要約してください。

    出力ルール:
    1. 必ず以下のJSON形式のみを出力すること
    2. JSON以外のテキスト（挨拶、説明、マークダウン）は一切出力しない
    3. titleは20文字以内
    4. summaryTextは主要なポイントを3〜5個含む

    出力形式:
    {"title": "要約タイトル", "summaryText": "要約本文"}

    例:
    {"title": "会議の議事録", "summaryText": "・新規プロジェクトの開始を決定\\n・予算は500万円以内\\n・次回会議は来週水曜日"}
    """

    init(modelPath: String = "") {
        if modelPath.isEmpty {
            self.objcBridge = LLamaBridgeObjC()
        } else {
            do {
                self.objcBridge = try LLamaBridgeObjC(modelPath: modelPath)
            } catch {
                NSLog("[LLamaBridge] Failed to load model at \(modelPath): \(error.localizedDescription)")
                self.objcBridge = LLamaBridgeObjC()
            }
        }
    }

    func generate(prompt: String, cancelState: CancelState) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            self.objcBridge.summarize(prompt, systemPrompt: self.systemPrompt) { summary, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: summary ?? "")
                }
            }
        }
    }
}
