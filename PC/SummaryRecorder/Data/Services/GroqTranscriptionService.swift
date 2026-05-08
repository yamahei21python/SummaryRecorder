import Foundation

struct GroqTranscriptionService: TranscriptionService {
    let apiKey: String
    let session: URLSession
    let maxChunkSize: Int

    init(apiKey: String, session: URLSession = .shared, maxChunkSize: Int = 25 * 1024 * 1024) {
        self.apiKey = apiKey
        self.session = session
        self.maxChunkSize = maxChunkSize
    }

    func transcribe(wavURL: URL, cancelState: CancelState) async throws -> String {
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: wavURL.path)[.size] as? Int64) ?? 0

        if Int64(maxChunkSize) >= fileSize {
            return try await transcribeSingleFile(wavURL: wavURL)
        } else {
            return try await transcribeSplitFile(wavURL: wavURL, cancelState: cancelState)
        }
    }

    // MARK: - Private

    private func transcribeSingleFile(wavURL: URL) async throws -> String {
        let request = try buildRequest(wavURL: wavURL)
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw TranscriptionError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw TranscriptionError.apiError(httpResponse.statusCode, body)
        }

        return try parseResponse(data: data)
    }

    private func transcribeSplitFile(wavURL: URL, cancelState: CancelState) async throws -> String {
        let chunks = try splitWavFile(url: wavURL, maxChunkSize: maxChunkSize)
        var transcriptions: [String] = []

        for chunkURL in chunks {
            guard !cancelState.isCancelled else {
                // Clean up remaining temp files
                for remaining in chunks where remaining != chunkURL {
                    try? FileManager.default.removeItem(at: remaining)
                }
                throw CancellationError()
            }

            let result = try await transcribeSingleFile(wavURL: chunkURL)
            transcriptions.append(result)
            try? FileManager.default.removeItem(at: chunkURL)
        }

        return transcriptions.joined(separator: "\n\n")
    }

    private func buildRequest(wavURL: URL) throws -> URLRequest {
        let url = URL(string: APIEndpoint.groqTranscription)!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 300

        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        let wavData = try Data(contentsOf: wavURL)

        body.appendMultipart(boundary: boundary, name: "file", filename: wavURL.lastPathComponent, mimeType: "audio/wav", data: wavData)
        body.appendMultipart(boundary: boundary, name: "model", data: GroqModel.transcription)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        request.httpBody = body
        return request
    }

    private func parseResponse(data: Data) throws -> String {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String else {
            throw TranscriptionError.invalidResponse
        }
        return text
    }

    private func splitWavFile(url: URL, maxChunkSize: Int) throws -> [URL] {
        let data = try Data(contentsOf: url)
        let header = data.prefix(44)
        let pcmData = data.dropFirst(44)
        let pcmPerChunk = maxChunkSize - 44

        guard pcmPerChunk > 0 else {
            throw TranscriptionError.fileTooLarge
        }

        var chunks: [URL] = []
        var offset = pcmData.startIndex
        var chunkIndex = 0

        while offset < pcmData.endIndex {
            let end = min(offset + pcmPerChunk, pcmData.endIndex)
            let chunkPCM = pcmData[offset..<end]
            let chunkSize = 44 + chunkPCM.count

            var chunkData = Data(capacity: chunkSize)
            chunkData.append(header)

            // Fix header sizes for this chunk
            var riffSize = UInt32(chunkSize - 8).littleEndian
            chunkData.replaceSubrange(4..<8, with: Data(bytes: &riffSize, count: 4))
            var dataSize = UInt32(chunkPCM.count).littleEndian
            chunkData.replaceSubrange(40..<44, with: Data(bytes: &dataSize, count: 4))

            chunkData.append(contentsOf: chunkPCM)

            let tempDir = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
            let chunkURL = tempDir.appendingPathComponent("chunk_\(chunkIndex).wav")
            try chunkData.write(to: chunkURL)
            chunks.append(chunkURL)

            offset = end
            chunkIndex += 1
        }

        return chunks
    }
}

// MARK: - Multipart Helper

private extension Data {
    mutating func appendMultipart(boundary: String, name: String, filename: String? = nil, mimeType: String? = nil, data: Data) {
        append("--\(boundary)\r\n".data(using: .utf8)!)
        var disposition = "Content-Disposition: form-data; name=\"\(name)\""
        if let filename {
            disposition += "; filename=\"\(filename)\""
        }
        append("\(disposition)\r\n".data(using: .utf8)!)
        if let mimeType {
            append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        } else {
            append("\r\n".data(using: .utf8)!)
        }
        append(data)
        append("\r\n".data(using: .utf8)!)
    }

    mutating func appendMultipart(boundary: String, name: String, data: String) {
        append("--\(boundary)\r\n".data(using: .utf8)!)
        append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
        append("\(data)\r\n".data(using: .utf8)!)
    }
}

// MARK: - Errors

enum TranscriptionError: Error, LocalizedError {
    case apiError(Int, String)
    case networkError(Error)
    case invalidResponse
    case fileTooLarge

    var errorDescription: String? {
        switch self {
        case .apiError(let code, let message): "Groq API error \(code): \(message)"
        case .networkError(let error): error.localizedDescription
        case .invalidResponse: "Invalid API response"
        case .fileTooLarge: "File too large for chunking"
        }
    }
}
