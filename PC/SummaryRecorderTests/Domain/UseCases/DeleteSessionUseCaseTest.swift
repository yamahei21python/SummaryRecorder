import XCTest
@testable import SummaryRecorder

final class DeleteSessionUseCaseTest: XCTestCase {
    private var tempDir: TempDirectory!
    private var sut: DeleteSessionUseCase!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
        sut = DeleteSessionUseCase(recordingsDirectory: tempDir.url)
    }

    override func tearDown() {
        tempDir.cleanup()
        sut = nil
        super.tearDown()
    }

    func testDelete_removesWavFile() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let wavURL = tempDir.writeWav(named: "test.wav", data: wavData)

        let session = Session(wavFileName: "test.wav")
        try sut.execute(session: session)

        XCTAssertFalse(FileManager.default.fileExists(atPath: wavURL.path))
    }

    func testDelete_wavFileMissing_doesNotThrow() {
        let session = Session(wavFileName: "nonexistent.wav")
        XCTAssertNoThrow(try sut.execute(session: session))
    }

    func testDelete_removesChunkFiles() throws {
        // Create chunk directory and files
        let chunkDir = tempDir.url.appendingPathComponent("session123")
        try FileManager.default.createDirectory(at: chunkDir, withIntermediateDirectories: true)
        let chunkURL = chunkDir.appendingPathComponent("chunk_0.wav")
        try Data([0, 1, 2]).write(to: chunkURL)

        let session = Session(
            sessionId: "session123",
            wavFileName: "session123.wav",
            status: SessionStatus.done.rawValue
        )
        session.chunks = [Chunk(chunkIndex: 0, status: ChunkStatus.done.rawValue, filePath: "session123/chunk_0.wav")]

        try sut.execute(session: session)

        XCTAssertFalse(FileManager.default.fileExists(atPath: chunkURL.path))
    }
}
