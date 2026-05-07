import XCTest
@testable import SummaryRecorder

final class WavHeaderSeekTest: XCTestCase {
    private var tempDir: TempDirectory!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
    }

    override func tearDown() {
        tempDir.cleanup()
        super.tearDown()
    }

    // MARK: - Append scenario: header not updated

    func testWavAfterAppend_headerRefersToOriginalSize() throws {
        // Create valid WAV
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "test.wav", data: wavData)

        // Append PCM data (simulate recording continuation)
        let additionalPCM = Data(count: 16000 * 2) // 1 sec 16kHz mono 16-bit
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(additionalPCM)
            handle.closeFile()
        }

        // Header still reflects original size (not updated yet)
        let fileData = try Data(contentsOf: url)
        let header = try WavHeader(data: fileData)

        let originalDataLength = Int64(16000 * 1 * 1 * 2) // 32_000 bytes
        XCTAssertEqual(header.dataLength, originalDataLength)

        // Actual file data size is larger
        let actualDataSize = Int64(fileData.count - 44)
        XCTAssertGreaterThan(actualDataSize, originalDataLength)
    }

    func testWavAfterMultipleAppends_headerStale() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "multi_append.wav", data: wavData)

        // Append twice
        for _ in 0..<2 {
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(Data(count: 16000 * 2))
                handle.closeFile()
            }
        }

        let header = try WavHeader(url: url)
        let fileSize = Int64((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0)

        // Header says original, file is 2 appends larger
        XCTAssertEqual(header.dataLength, 32_000)
        XCTAssertEqual(fileSize, 44 + 32_000 + 32_000 * 2)
    }

    // MARK: - Repair scenario

    func testWavAfterRepair_headerMatchesFileSize() throws {
        let wavData = TestWavFactory.createWav(durationSeconds: 1)
        let url = tempDir.writeWav(named: "repair_test.wav", data: wavData)

        // Append data without updating header
        let additionalPCM = Data(count: 16000 * 2)
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(additionalPCM)
            handle.closeFile()
        }

        // Repair fixes the header
        let result = try WavRepair.repair(url: url)
        XCTAssertTrue(result)

        let header = try WavHeader(url: url)
        let fileSize = Int64((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0)
        XCTAssertEqual(header.dataLength, fileSize - 44)
        XCTAssertEqual(header.riffChunkSize, fileSize - 8)
    }
}
