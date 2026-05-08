import Foundation

struct GeminiSummarizationService: SummarizationService {
    let apiKey: String
    let session: URLSession
    let summaryPrompt: String

    init(apiKey: String, summaryPrompt: String, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.summaryPrompt = summaryPrompt
        self.session = session
    }

    func summarize(text: String, cancelState: CancelState) async throws -> SummaryOutput {
        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        let url = URL(string: APIEndpoint.geminiSummarization)!

        let prompt = """
        \(summaryPrompt)

        テキスト:
        \(text)
        """

        let requestBody: [String: Any] = [
            "contents": [["parts": [["text": prompt]]]],
            "generationConfig": [
                "responseMimeType": "application/json",
                "responseSchema": [
                    "type": "object",
                    "properties": [
                        "title": ["type": "string"],
                        "summaryText": ["type": "string"]
                    ],
                    "required": ["title", "summaryText"]
                ]
            ]
        ]

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120
        request.httpBody = try JSONSerialization.data(withJSONObject: requestBody)

        let (data, response) = try await session.data(for: request)

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
            let body = String(data: data, encoding: .utf8) ?? ""
            throw SummarizationServiceError.apiError(statusCode, body)
        }

        return try parseResponse(data: data)
    }

    // MARK: - Private

    private func parseResponse(data: Data) throws -> SummaryOutput {
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        guard let candidates = json?["candidates"] as? [[String: Any]],
              let content = candidates.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]],
              let text = parts.first?["text"] as? String else {
            throw SummarizationServiceError.invalidResponse
        }

        guard let jsonData = text.data(using: .utf8) else {
            throw SummarizationServiceError.invalidJSON
        }

        let parsed = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
        var title = parsed?["title"] as? String ?? ""
        let summaryText = parsed?["summaryText"] as? String ?? ""

        guard !title.isEmpty || !summaryText.isEmpty else {
            throw SummarizationServiceError.invalidJSON
        }

        return SummaryOutput(
            title: title.isEmpty ? formatFallbackTitle() : title,
            summaryText: summaryText
        )
    }

    private func formatFallbackTitle() -> String {
        AppFormatters.fallbackTitle()
    }
}

// MARK: - Errors

enum SummarizationServiceError: Error, LocalizedError {
    case apiError(Int, String)
    case invalidResponse
    case invalidJSON
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .apiError(let code, let msg): "Gemini API error \(code): \(msg)"
        case .invalidResponse: "Invalid API response format"
        case .invalidJSON: "Failed to parse JSON output"
        case .networkError(let err): err.localizedDescription
        }
    }
}
