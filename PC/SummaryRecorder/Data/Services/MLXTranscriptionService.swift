import Foundation

/// Calls lightning-whisper-mlx via Python subprocess
struct MLXTranscriptionService: TranscriptionService {
    let modelName: String

    init(modelName: String = "small") {
        self.modelName = modelName
    }

    func transcribe(wavURL: URL, cancelState: CancelState) async throws -> String {
        guard !cancelState.isCancelled else { throw CancellationError() }

        guard let scriptURL = Bundle.main.url(forResource: "transcribe", withExtension: "py") else {
            throw MLXError.scriptNotFound
        }

        return try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
            process.arguments = [scriptURL.path, wavURL.path, "--model", modelName]

            let outputPipe = Pipe()
            let errorPipe = Pipe()
            process.standardOutput = outputPipe
            process.standardError = errorPipe

            // waitUntilExit前に別スレッドで読む（デッドロック防止）
            let outputFuture = DispatchIO.read(pipe: outputPipe)
            let errorFuture = DispatchIO.read(pipe: errorPipe)

            do {
                try process.run()
                process.waitUntilExit()

                let outputData = outputFuture()
                let errorData = errorFuture()

                let stdout = String(data: outputData, encoding: .utf8) ?? ""
                let stderr = String(data: errorData, encoding: .utf8) ?? ""
                NSLog("[MLX] exit=\(process.terminationStatus) stdout='\(stdout)' stderr='\(stderr)'")

                guard process.terminationStatus == 0 else {
                    let stderr = String(data: errorData, encoding: .utf8) ?? ""
                    continuation.resume(throwing: MLXError.processError(stderr))
                    return
                }

                guard let result = try? JSONSerialization.jsonObject(with: outputData) as? [String: String] else {
                    continuation.resume(throwing: MLXError.invalidOutput)
                    return
                }

                if let error = result["error"] {
                    continuation.resume(throwing: MLXError.transcriptionError(error))
                    return
                }

                guard let text = result["text"], !text.isEmpty else {
                    continuation.resume(throwing: MLXError.emptyResult)
                    return
                }

                continuation.resume(returning: text)
            } catch {
                continuation.resume(throwing: MLXError.processError(error.localizedDescription))
            }
        }
    }
}

// MARK: - Errors

enum MLXError: Error, LocalizedError {
    case scriptNotFound
    case processError(String)
    case invalidOutput
    case transcriptionError(String)
    case emptyResult

    var errorDescription: String? {
        switch self {
        case .scriptNotFound: "MLX transcription script not found"
        case .processError(let msg): "MLX process error: \(msg)"
        case .invalidOutput: "MLX invalid output format"
        case .transcriptionError(let msg): msg
        case .emptyResult: "MLX returned empty transcription"
        }
    }
}

// MARK: - Pipe Reader Helper

private enum DispatchIO {
    /// Pipeを別スレッドで非同期読み取り、同期的に返すクロージャを返す
    static func read(pipe: Pipe) -> () -> Data {
        let semaphore = DispatchSemaphore(value: 0)
        var result = Data()

        DispatchQueue.global().async {
            result = pipe.fileHandleForReading.readDataToEndOfFile()
            semaphore.signal()
        }

        return {
            semaphore.wait()
            return result
        }
    }
}
