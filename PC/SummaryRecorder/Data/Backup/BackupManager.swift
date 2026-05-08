import Foundation
import Compression

struct BackupManager: Sendable {
    let recordingsDirectory: URL

    init(recordingsDirectory: URL? = nil) {
        self.recordingsDirectory = recordingsDirectory ?? AppPaths.recordingsDirectory
    }

    /// Export sessions + WAV files to ZIP
    func export(sessions: [Session], outputURL: URL) async throws {
        // Estimate ZIP size for warning
        let estimatedSize = estimateZipSize(sessions: sessions)
        if estimatedSize > 10 * 1024 * 1024 * 1024 { // 10GB
            // Caller should show warning
        }

        let fm = FileManager.default

        // Create temp directory for backup contents
        let tempDir = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: tempDir) }

        // Write JSON (sessions only, NO chunks)
        let sessionsJSON = try encodeSessions(sessions)
        let jsonURL = tempDir.appendingPathComponent("sessions.json")
        try sessionsJSON.write(to: jsonURL, options: .atomic)

        // Copy WAV files
        let audioDir = tempDir.appendingPathComponent("audio")
        try fm.createDirectory(at: audioDir, withIntermediateDirectories: true)
        for session in sessions {
            let sourceURL = recordingsDirectory.appendingPathComponent(session.wavFileName)
            if fm.fileExists(atPath: sourceURL.path) {
                let destURL = audioDir.appendingPathComponent(session.wavFileName)
                try fm.copyItem(at: sourceURL, to: destURL)
            }
        }

        // Create ZIP using shell command
        try createZip(from: tempDir, to: outputURL)
    }

    /// Import sessions from ZIP backup
    func importFrom(url: URL, existingSessionIds: Set<String>) async throws -> ImportResult {
        let fm = FileManager.default
        let tempDir = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: tempDir) }

        // Extract ZIP
        try extractZip(from: url, to: tempDir)

        // Parse sessions.json
        let jsonURL = tempDir.appendingPathComponent("sessions.json")
        guard fm.fileExists(atPath: jsonURL.path) else {
            throw BackupError.invalidBackupFormat
        }

        let jsonData = try Data(contentsOf: jsonURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let sessions = try decoder.decode([BackupSession].self, from: jsonData)

        var imported = 0
        var skipped = 0
        var restoredSessions: [Session] = []

        for backupSession in sessions {
            if existingSessionIds.contains(backupSession.sessionId) {
                skipped += 1
                continue
            }

            // Copy WAV file
            let audioURL = tempDir.appendingPathComponent("audio/\(backupSession.wavFileName)")
            let destURL = recordingsDirectory.appendingPathComponent(backupSession.wavFileName)
            if fm.fileExists(atPath: audioURL.path) {
                try? fm.createDirectory(at: recordingsDirectory, withIntermediateDirectories: true)
                try fm.copyItem(at: audioURL, to: destURL)
            }

            let status: SessionStatus
            if backupSession.status == SessionStatus.summarizing.rawValue ||
               backupSession.status == SessionStatus.recorded.rawValue {
                status = .error
            } else if let s = SessionStatus(rawValue: backupSession.status) {
                status = s
            } else {
                status = .error
            }

            let session = Session(
                sessionId: backupSession.sessionId,
                createdAt: backupSession.createdAt,
                title: backupSession.title,
                isTitleEdited: backupSession.isTitleEdited,
                summaryText: backupSession.summaryText,
                transcriptionText: backupSession.transcriptionText,
                wavFileName: backupSession.wavFileName,
                durationMs: backupSession.durationMs,
                status: status,
                errorMessage: status == .error ? "リストア後: 再処理が必要" : backupSession.errorMessage
            )
            restoredSessions.append(session)
            imported += 1
        }

        return ImportResult(imported: imported, skipped: skipped, sessions: restoredSessions)
    }

    // MARK: - Private

    private func encodeSessions(_ sessions: [Session]) throws -> Data {
        let backupSessions = sessions.map { session -> BackupSession in
            BackupSession(
                sessionId: session.sessionId,
                createdAt: session.createdAt,
                title: session.title,
                isTitleEdited: session.isTitleEdited,
                summaryText: session.summaryText,
                transcriptionText: session.transcriptionText,
                wavFileName: session.wavFileName,
                durationMs: session.durationMs,
                status: session.status.rawValue,
                errorMessage: session.errorMessage
            )
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(backupSessions)
    }

    private func estimateZipSize(sessions: [Session]) -> Int64 {
        var size: Int64 = 0
        let fm = FileManager.default
        for session in sessions {
            let url = recordingsDirectory.appendingPathComponent(session.wavFileName)
            if let attrs = try? fm.attributesOfItem(atPath: url.path),
               let fileSize = attrs[.size] as? Int64 {
                size += fileSize
            }
        }
        return size
    }

    private func createZip(from sourceDir: URL, to outputURL: URL) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        process.arguments = ["-r", "-y", outputURL.path, "."]
        process.currentDirectoryURL = sourceDir
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw BackupError.zipCreationFailed
        }
    }

    private func extractZip(from zipURL: URL, to destDir: URL) throws {
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        process.arguments = ["-o", "-q", zipURL.path, "-d", destDir.path]
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw BackupError.zipExtractionFailed
        }
    }
}

struct BackupSession: Codable, Sendable {
    let sessionId: String
    let createdAt: Date
    let title: String
    let isTitleEdited: Bool
    let summaryText: String
    let transcriptionText: String
    let wavFileName: String
    let durationMs: Double
    let status: String
    let errorMessage: String?
}

struct ImportResult: @unchecked Sendable {
    let imported: Int
    let skipped: Int
    let sessions: [Session]
}

enum BackupError: Error, LocalizedError {
    case invalidBackupFormat
    case zipCreationFailed
    case zipExtractionFailed

    var errorDescription: String? {
        switch self {
        case .invalidBackupFormat: "Invalid backup format"
        case .zipCreationFailed: "Failed to create ZIP"
        case .zipExtractionFailed: "Failed to extract ZIP"
        }
    }
}
