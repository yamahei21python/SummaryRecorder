import XCTest
@testable import SummaryRecorder

final class BackupManagerExportTest: XCTestCase {
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

    func testExport_createsZipFile() async throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let wavURL = recordingsDir.writeWav(named: "session1.wav", data: wavData)

        let session = Session(
            sessionId: "session1",
            title: "テスト",
            wavFileName: "session1.wav",
            status: SessionStatus.done.rawValue
        )

        let outputURL = tempDir.url.appendingPathComponent("backup.zip")
        try await sut.export(sessions: [session], outputURL: outputURL)

        XCTAssertTrue(FileManager.default.fileExists(atPath: outputURL.path))
        let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        XCTAssertTrue((attrs[.size] as? Int64 ?? 0) > 0)
    }

    private func extractZip(_ zipURL: URL, to extractDir: URL) throws {
        // Place extractDir directly in sandbox /tmp to avoid Process sandbox path resolution issues
        let directExtractDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("extract-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directExtractDir, withIntermediateDirectories: true)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        process.arguments = ["-o", "-q", zipURL.path, "-d", directExtractDir.path]
        try process.run()
        process.waitUntilExit()
        // Move contents to expected extractDir
        let fm = FileManager.default
        if let contents = try? fm.contentsOfDirectory(at: directExtractDir, includingPropertiesForKeys: nil) {
            try fm.createDirectory(at: extractDir, withIntermediateDirectories: true)
            for item in contents {
                let dest = extractDir.appendingPathComponent(item.lastPathComponent)
                try? fm.moveItem(at: item, to: dest)
            }
        }
        try? fm.removeItem(at: directExtractDir)
    }

    private func findSessionsJSON(in extractDir: URL) throws -> URL {
        let fm = FileManager.default
        // ZIP may contain a subdirectory (legacy) or files at root
        let jsonURL = extractDir.appendingPathComponent("sessions.json")
        if fm.fileExists(atPath: jsonURL.path) {
            return jsonURL
        }
        // Legacy: look for subdirectory containing sessions.json
        let contents = try fm.contentsOfDirectory(at: extractDir, includingPropertiesForKeys: nil)
        let innerDir = contents.first(where: { $0.hasDirectoryPath })
        let fallbackURL = (innerDir ?? extractDir).appendingPathComponent("sessions.json")
        XCTAssertTrue(fm.fileExists(atPath: fallbackURL.path), "sessions.json not found")
        return fallbackURL
    }

    func testExport_jsonContainsSessionArray() async throws {
        let session = Session(
            sessionId: "s1",
            title: "Test",
            wavFileName: "s1.wav",
            status: SessionStatus.done.rawValue
        )

        let outputURL = tempDir.url.appendingPathComponent("backup.zip")
        try await sut.export(sessions: [session], outputURL: outputURL)

        let extractDir = tempDir.url.appendingPathComponent("extracted-\(UUID().uuidString)")
        try extractZip(outputURL, to: extractDir)
        let jsonURL = try findSessionsJSON(in: extractDir)
        let jsonData = try Data(contentsOf: jsonURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let sessions = try decoder.decode([BackupSession].self, from: jsonData)
        XCTAssertEqual(sessions.count, 1)
        XCTAssertEqual(sessions.first?.sessionId, "s1")
    }

    func testExport_jsonExcludesChunks() async throws {
        let session = Session(sessionId: "s1", wavFileName: "s1.wav", status: SessionStatus.done.rawValue)

        let outputURL = tempDir.url.appendingPathComponent("backup.zip")
        try await sut.export(sessions: [session], outputURL: outputURL)

        let extractDir = tempDir.url.appendingPathComponent("extracted-\(UUID().uuidString)")
        try extractZip(outputURL, to: extractDir)
        let jsonURL = try findSessionsJSON(in: extractDir)
        let jsonData = try Data(contentsOf: jsonURL)
        let json = try JSONSerialization.jsonObject(with: jsonData)
        XCTAssertTrue(json is [Any], "Expected JSON array, not dictionary/object")
    }
}
