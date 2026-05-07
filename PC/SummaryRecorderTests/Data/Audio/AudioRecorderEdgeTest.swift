import XCTest
@testable import SummaryRecorder

final class AudioRecorderEdgeTest: XCTestCase {
    func testRecorderError_descriptions() {
        XCTAssertEqual(RecorderError.alreadyRecording.errorDescription, "Already recording")
        XCTAssertEqual(RecorderError.notRecording.errorDescription, "Not currently recording")
        XCTAssertEqual(RecorderError.permissionDenied.errorDescription, "Microphone permission denied")
        XCTAssertEqual(RecorderError.fileCreationFailed.errorDescription, "Failed to create output file")
        XCTAssertEqual(RecorderError.noOutputFile.errorDescription, "No output file available")
    }
}
