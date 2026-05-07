import XCTest
@testable import SummaryRecorder

final class WavHeaderTest: XCTestCase {
    private var tempDir: TempDirectory!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
    }

    override func tearDown() {
        tempDir.cleanup()
        super.tearDown()
    }

    func testParseHeader_readsCorrectSampleRate() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1, sampleRate: 16000)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        let header = try WavHeader(url: url)
        XCTAssertEqual(header.sampleRate, 16000)
    }

    func testParseHeader_readsCorrectChannels() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1, sampleRate: 16000, channels: 2)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        let header = try WavHeader(url: url)
        XCTAssertEqual(header.channels, 2)
    }

    func testParseHeader_readsCorrectBitsPerSample() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        let header = try WavHeader(url: url)
        XCTAssertEqual(header.bitsPerSample, 16)
    }

    func testParseHeader_readsCorrectDataLength() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 2, sampleRate: 16000)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        let header = try WavHeader(url: url)
        let expectedDataLength = Int64(16000 * 2 * 1 * 2)
        XCTAssertEqual(header.dataLength, expectedDataLength)
    }

    func testParseHeader_readsCorrectRiffChunkSize() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        let header = try WavHeader(url: url)
        XCTAssertEqual(header.riffChunkSize, header.dataLength + 36)
    }

    func testInvalidWav_throwsError() {
        let data = Data([0x00, 0x00, 0x00, 0x00])
        let url = tempDir.writeWav(named: "invalid.wav", data: data)

        XCTAssertThrowsError(try WavHeader(url: url))
    }

    func testEmptyFile_throwsError() {
        let url = tempDir.url.appendingPathComponent("empty.wav")
        try! Data().write(to: url)

        XCTAssertThrowsError(try WavHeader(url: url))
    }
}
