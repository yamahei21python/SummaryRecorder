import XCTest
@testable import SummaryRecorder

final class SessionStatusTest: XCTestCase {
    func testAllCasesExist() {
        let allCases = SessionStatus.allCases
        XCTAssertEqual(allCases.count, 4)
        XCTAssertTrue(allCases.contains(.recorded))
        XCTAssertTrue(allCases.contains(.summarizing))
        XCTAssertTrue(allCases.contains(.done))
        XCTAssertTrue(allCases.contains(.error))
    }

    func testRawValues() {
        XCTAssertEqual(SessionStatus.recorded.rawValue, "recorded")
        XCTAssertEqual(SessionStatus.summarizing.rawValue, "summarizing")
        XCTAssertEqual(SessionStatus.done.rawValue, "done")
        XCTAssertEqual(SessionStatus.error.rawValue, "error")
    }

    func testCodableRoundTrip() throws {
        for status in SessionStatus.allCases {
            let encoded = try JSONEncoder().encode(status)
            let decoded = try JSONDecoder().decode(SessionStatus.self, from: encoded)
            XCTAssertEqual(decoded, status)
        }
    }

    func testSendable() {
        let status: SessionStatus = .recorded
        let expectation = expectation(description: "sendable")
        Task {
            XCTAssertEqual(status, .recorded)
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 1.0)
    }
}
