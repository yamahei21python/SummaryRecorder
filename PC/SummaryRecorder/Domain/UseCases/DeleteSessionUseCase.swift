import Foundation

struct DeleteSessionUseCase: Sendable {
    let recordingsDirectory: URL

    init(recordingsDirectory: URL? = nil) {
        self.recordingsDirectory = recordingsDirectory ?? AppPaths.recordingsDirectory
    }

    func execute(session: Session) throws {
        let fm = FileManager.default

        // Delete main WAV file
        let wavURL = recordingsDirectory.appendingPathComponent(session.wavFileName)
        if fm.fileExists(atPath: wavURL.path) {
            try fm.removeItem(at: wavURL)
        }

        // Delete chunk files
        for chunk in session.chunks where !chunk.filePath.isEmpty {
            let chunkURL = recordingsDirectory.appendingPathComponent(chunk.filePath)
            if fm.fileExists(atPath: chunkURL.path) {
                try? fm.removeItem(at: chunkURL)
            }
        }

        // Remove chunk directory if empty
        if !session.sessionId.isEmpty {
            let chunkDir = recordingsDirectory.appendingPathComponent(session.sessionId)
            if fm.fileExists(atPath: chunkDir.path) {
                try? fm.removeItem(at: chunkDir)
            }
        }
    }
}
