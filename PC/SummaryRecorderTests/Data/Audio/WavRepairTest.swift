import XCTest
@testable import SummaryRecorder

final class WavRepairTest: XCTestCase {
    private var tempDir: TempDirectory!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
    }

    override func tearDown() {
        tempDir.cleanup()
        super.tearDown()
    }

    func testRepairWav_withZeroDataLength_fixesHeader() throws {
        let wavData = TestWavFactory.createCorruptWav_zeroDataLength()
        let url = tempDir.writeWav(named: "corrupt.wav", data: wavData)

        let result = try WavRepair.repair(url: url)
        XCTAssertTrue(result)

        let header = try WavHeader(url: url)
        XCTAssertGreaterThan(header.dataLength, 0)
        XCTAssertEqual(header.riffChunkSize, header.dataLength + 36)
    }

    func testRepairWav_withIncorrectRiffChunkSize_fixesHeader() throws {
        let wavData = TestWavFactory.createCorruptWav_badRiffSize()
        let url = tempDir.writeWav(named: "corrupt.wav", data: wavData)

        let result = try WavRepair.repair(url: url)
        XCTAssertTrue(result)

        let header = try WavHeader(url: url)
        XCTAssertEqual(header.riffChunkSize, header.dataLength + 36)
    }

    func testRepairWav_withEmptyFile_returnsFalse() throws {
        let url = tempDir.url.appendingPathComponent("empty.wav")
        try! Data().write(to: url)

        let result = try WavRepair.repair(url: url)
        XCTAssertFalse(result)
    }

    func testRepairWav_withFileShorterThan44Bytes_returnsFalse() throws {
        let data = TestWavFactory.createCorruptWav_truncated()
        let url = tempDir.writeWav(named: "truncated.wav", data: data)

        let result = try WavRepair.repair(url: url)
        XCTAssertFalse(result)
    }

    func testRepairWav_withValidWav_doesNotModify() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "valid.wav", data: wavData)
        let originalData = try Data(contentsOf: url)

        let result = try WavRepair.repair(url: url)
        XCTAssertTrue(result)

        let repairedData = try Data(contentsOf: url)
        XCTAssertEqual(originalData, repairedData)
    }
}
