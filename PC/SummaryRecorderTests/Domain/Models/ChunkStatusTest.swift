import XCTest
@testable import SummaryRecorder

final class ChunkStatusTest: XCTestCase {
    func testAllCasesExist() {
        let allCases = ChunkStatus.allCases
        XCTAssertEqual(allCases.count, 4)
        XCTAssertTrue(allCases.contains(.pending))
        XCTAssertTrue(allCases.contains(.uploading))
        XCTAssertTrue(allCases.contains(.done))
        XCTAssertTrue(allCases.contains(.failed))
    }

    func testRawValues() {
        XCTAssertEqual(ChunkStatus.pending.rawValue, "pending")
        XCTAssertEqual(ChunkStatus.uploading.rawValue, "uploading")
        XCTAssertEqual(ChunkStatus.done.rawValue, "done")
        XCTAssertEqual(ChunkStatus.failed.rawValue, "failed")
    }

    func testCodableRoundTrip() throws {
        for status in ChunkStatus.allCases {
            let encoded = try JSONEncoder().encode(status)
            let decoded = try JSONDecoder().decode(ChunkStatus.self, from: encoded)
            XCTAssertEqual(decoded, status)
        }
    }

    func testSendable() {
        let status: ChunkStatus = .pending
        let expectation = expectation(description: "sendable")
        Task {
            XCTAssertEqual(status, .pending)
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 1.0)
    }
}
