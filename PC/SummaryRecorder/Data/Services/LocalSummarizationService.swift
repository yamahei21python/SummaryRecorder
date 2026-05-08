import Foundation

final class LocalSummarizationService: SummarizationService {
    private let llamaBridge: LLamaBridgeProtocol

    init(llamaBridge: LLamaBridgeProtocol = LLamaBridgeSwift()) {
        self.llamaBridge = llamaBridge
    }

    convenience init(modelPath: String, systemPrompt: String = LLamaBridgeSwift.defaultSystemPrompt) {
        let bridge = LLamaBridgeSwift(modelPath: modelPath, systemPrompt: systemPrompt)
        self.init(llamaBridge: bridge)
    }

    convenience init(systemPrompt: String = LLamaBridgeSwift.defaultSystemPrompt) {
        let bridge = LLamaBridgeSwift(systemPrompt: systemPrompt)
        self.init(llamaBridge: bridge)
    }

    func summarize(text: String, cancelState: CancelState) async throws -> SummaryOutput {
        cancelState.markCallbackReached()

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return SummaryOutput(title: formatFallbackTitle(), summaryText: "文字起こしテキストが空です")
        }

        let json = try await llamaBridge.generate(prompt: text, cancelState: cancelState)

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        return try parseOutput(json: json)
    }

    // MARK: - Private

    private func parseOutput(json: String) throws -> SummaryOutput {
        // Empty output guard
        guard !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return SummaryOutput(title: formatFallbackTitle(), summaryText: "要約結果が空です")
        }

        // Step 0: Strip thinking content (double-guard in case ObjC misses)
        var raw = stripThinkingContent(json)

        // Step 1: Extract JSON from markdown code blocks
        raw = raw
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
            let summaryText = stringifyValue(parsed["summaryText"])
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

        // Step 5: Fallback — cleaned text as summaryText (strip any remaining think tags)
        return SummaryOutput(title: formatFallbackTitle(), summaryText: raw.isEmpty ? "要約結果が空です" : raw)
    }

    // MARK: - JSON Extraction Helpers

    /// Strip thinking content from model output (double-guard)
    /// Uses string operations (NSRegularExpression can't match | in ICU regex)
    private func stripThinkingContent(_ text: String) -> String {
        var result = text
        let startTag = "<|channel>"
        let endTag = "<channel|>"

        // Remove all <|channel>...<channel|> blocks (including thought content)
        while let startRange = result.range(of: startTag) {
            if let endRange = result.range(of: endTag, range: startRange.lowerBound..<result.endIndex) {
                result.removeSubrange(startRange.lowerBound..<endRange.upperBound)
            } else {
                // Unclosed block — remove from startTag to end
                result.removeSubrange(startRange.lowerBound..<result.endIndex)
                break
            }
        }
        // Pattern 2: trailing <turn|>
        result = result.replacingOccurrences(of: "<turn|>", with: "")
        return result
    }

    /// Find first JSON object containing "title" from raw text
    private func extractJSON(from text: String) -> String? {
        guard let start = text.firstIndex(of: "{") else { return nil }
        var depth = 0
        for idx in text.indices[start...] {
            if text[idx] == "{" { depth += 1 }
            if text[idx] == "}" { depth -= 1 }
            if depth == 0 {
                let candidate = String(text[start...idx])
                return candidate.contains("\"title\"") ? candidate : nil
            }
        }
        return nil
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
        let summaryText = stringifyValue(parsed["summaryText"])
        guard !title.isEmpty || !summaryText.isEmpty else { return nil }

        return SummaryOutput(
            title: title.isEmpty ? formatFallbackTitle() : title,
            summaryText: summaryText.isEmpty ? text : summaryText
        )
    }

    private func formatFallbackTitle() -> String {
        AppFormatters.fallbackTitle()
    }

    /// Convert Any JSON value to String (handles String, [String], nested values)
    private func stringifyValue(_ value: Any?) -> String {
        guard let value else { return "" }
        if let str = value as? String { return str }
        if let arr = value as? [String] { return arr.joined(separator: "\n") }
        if let arr = value as? [Any] { return arr.compactMap { stringifyValue($0) }.joined(separator: "\n") }
        return ""
    }
}

// MARK: - Protocol for testability

protocol LLamaBridgeProtocol: Sendable {
    func generate(prompt: String, cancelState: CancelState) async throws -> String
}

// MARK: - Swift Bridge (Phase 5: connects to LLamaBridgeObjC)

final class LLamaBridgeSwift: LLamaBridgeProtocol, @unchecked Sendable {
    private let objcBridge: LLamaBridgeObjC
    private let systemPrompt: String

    static let defaultSystemPrompt = """
    以下のテキストを要約してください。

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

    init(modelPath: String = "", systemPrompt: String = defaultSystemPrompt) {
        self.systemPrompt = systemPrompt
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
