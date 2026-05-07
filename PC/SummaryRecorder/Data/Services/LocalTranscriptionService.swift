import Foundation

final class LocalTranscriptionService: TranscriptionService {
    private let whisperBridge: WhisperBridgeProtocol

    init(whisperBridge: WhisperBridgeProtocol = WhisperBridgeSwift()) {
        self.whisperBridge = whisperBridge
    }

    // Convenience init with model path for production use
    convenience init(modelPath: String) {
        let bridge = WhisperBridgeSwift(modelPath: modelPath)
        self.init(whisperBridge: bridge)
    }

    func transcribe(wavURL: URL, cancelState: CancelState) async throws -> String {
        cancelState.markCallbackReached()

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        let text = try await whisperBridge.transcribe(wavPath: wavURL.path, cancelState: cancelState)

        guard !cancelState.isCancelled else {
            throw CancellationError()
        }

        return text
    }
}

// MARK: - Protocol for testability

protocol WhisperBridgeProtocol: Sendable {
    func transcribe(wavPath: String, cancelState: CancelState) async throws -> String
}

// MARK: - Swift Bridge (Phase 5: connects to WhisperBridgeObjC)

final class WhisperBridgeSwift: WhisperBridgeProtocol, @unchecked Sendable {
    private let objcBridge: WhisperBridgeObjC

    init(modelPath: String = "") {
        if modelPath.isEmpty {
            // Cloud-only mode: lazy init that will fail gracefully
            self.objcBridge = WhisperBridgeObjC()
        } else {
            do {
                self.objcBridge = try WhisperBridgeObjC(modelPath: modelPath)
            } catch {
                NSLog("[WhisperBridge] Failed to load model at \(modelPath): \(error.localizedDescription)")
                self.objcBridge = WhisperBridgeObjC()
            }
        }
    }

    func transcribe(wavPath: String, cancelState: CancelState) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            self.objcBridge.transcribeWav(wavPath) { text, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: text ?? "")
                }
            }
        }
    }
}
