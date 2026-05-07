import XCTest
@testable import SummaryRecorder

final class BackupManagerImportTest: XCTestCase {
    private var tempDir: TempDirectory!
    private var recordingsDir: TempDirectory!
    private var sut: BackupManager!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
        recordingsDir = TempDirectory()
        sut = BackupManager(recordingsDirectory: recordingsDir.url)
    }

    override func tearDown() {
        tempDir.cleanup()
        recordingsDir.cleanup()
        super.tearDown()
    }

    private func createBackupZip(exportDirName: String, sessions: [BackupSession], wavData: Data? = nil) throws -> URL {
        let exportDir = tempDir.url.appendingPathComponent(exportDirName)
        try FileManager.default.createDirectory(at: exportDir, withIntermediateDirectories: true)

        // Write sessions.json
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let json = try encoder.encode(sessions)
        try json.write(to: exportDir.appendingPathComponent("sessions.json"))

        // Write audio files
        let audioDir = exportDir.appendingPathComponent("audio")
        try FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
        for session in sessions {
            if let data = wavData {
                try data.write(to: audioDir.appendingPathComponent(session.wavFileName))
            }
        }

        // Create ZIP: cd into exportDir and zip contents (no parent wrapper)
        let zipURL = tempDir.url.appendingPathComponent("backup.zip")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        process.arguments = ["-r", "-y", zipURL.path, "."]
        process.currentDirectoryURL = exportDir
        try process.run()
        process.waitUntilExit()

        return zipURL
    }

    func testImport_importsSessionsFromZip() async throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let session = BackupSession(
            sessionId: "s1", createdAt: Date(), title: "Import Test",
            isTitleEdited: false, summaryText: "Summary", transcriptionText: "Text",
            wavFileName: "s1.wav", durationMs: 1000, status: "done", errorMessage: nil
        )

        let zipURL = try createBackupZip(exportDirName: "export", sessions: [session], wavData: wavData)

        let result = try await sut.importFrom(url: zipURL, existingSessionIds: [])
        XCTAssertEqual(result.imported, 1)
        XCTAssertEqual(result.sessions.first?.title, "Import Test")
    }

    func testImport_duplicateSessionId_skips() async throws {
        let session = BackupSession(
            sessionId: "s1", createdAt: Date(), title: "Dup",
            isTitleEdited: false, summaryText: "", transcriptionText: "",
            wavFileName: "s1.wav", durationMs: 0, status: "done", errorMessage: nil
        )

        let zipURL = try createBackupZip(exportDirName: "export", sessions: [session])

        let result = try await sut.importFrom(url: zipURL, existingSessionIds: ["s1"])
        XCTAssertEqual(result.imported, 0)
        XCTAssertEqual(result.skipped, 1)
    }

    func testImport_summarizingSession_marksAsError() async throws {
        let session = BackupSession(
            sessionId: "s2", createdAt: Date(), title: "Was Summarizing",
            isTitleEdited: false, summaryText: "", transcriptionText: "some text",
            wavFileName: "s2.wav", durationMs: 5000, status: "summarizing", errorMessage: nil
        )

        let zipURL = try createBackupZip(exportDirName: "export", sessions: [session])

        let result = try await sut.importFrom(url: zipURL, existingSessionIds: [])
        XCTAssertEqual(result.sessions.first?.status, SessionStatus.error.rawValue)
    }
}
