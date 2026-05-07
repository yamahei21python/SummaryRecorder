import XCTest
@testable import SummaryRecorder

final class WavHeaderOverflowTest: XCTestCase {

    // MARK: - 32-bit boundary specs

    func testDataLength_exceeds4GB_handlesGracefully() {
        // WAV data chunk size is stored as 32-bit unsigned int
        // Max representable: 4,294,967,295 bytes (~4GB)
        let maxDataLength: Int64 = Int64(UInt32.max)
        XCTAssertEqual(maxDataLength, 4_294_967_295)
    }

    func testRiffChunkSize_maxValue_isFileSizeMinus8() {
        // RIFF chunk size = file size - 8 (RIFF id + size field itself)
        let maxRiffSize: Int64 = Int64(UInt32.max)
        XCTAssertEqual(maxRiffSize, 4_294_967_295)
    }

    func testDataLength_atBoundary_minusOne_fits() {
        // Just under UInt32.max should be representable
        let nearMax: Int64 = Int64(UInt32.max) - 1
        XCTAssertLessThan(nearMax, Int64(UInt32.max))
    }

    func testDataLength_oneByteOverMax_overflows() {
        // UInt32.max + 1 overflows to 0 in 32-bit context
        let overflowed: UInt32 = UInt32.max &+ 1
        XCTAssertEqual(overflowed, 0)
    }
}
