import XCTest
@testable import SummaryRecorder

final class AudioRecorderTest: XCTestCase {
    private var tempDir: TempDirectory!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
    }

    override func tearDown() {
        tempDir.cleanup()
        super.tearDown()
    }

    func testInitialState_isIdle() async throws {
        let recorder = await AudioRecorder()
        let state = await recorder.state
        XCTAssertEqual(state, .idle)
    }

    func testRecorderState_allCases() {
        let allCases = [RecorderState.idle, .recording, .paused]
        XCTAssertEqual(allCases.count, 3)
    }

    func testRecorderError_allCases() {
        let errors: [RecorderError] = [.alreadyRecording, .notRecording, .permissionDenied, .fileCreationFailed, .noOutputFile, .invalidState]
        XCTAssertEqual(errors.count, 6)
    }

    func testWavWriter_finalizeHeader() throws {
        // Create a WAV with zero data length header
        let wavData = TestWavFactory.createCorruptWav_zeroDataLength()
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        try WavWriter.finalizeHeader(url: url)

        let header = try WavHeader(url: url)
        XCTAssertGreaterThan(header.dataLength, 0)
        XCTAssertEqual(header.riffChunkSize, header.dataLength + 36)
    }
}
