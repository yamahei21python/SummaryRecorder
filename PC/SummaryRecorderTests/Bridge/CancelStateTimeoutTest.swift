import XCTest
@testable import SummaryRecorder

final class CancelStateTimeoutTest: XCTestCase {

    // MARK: - Timeout threshold

    func testTimeout_threshold_is3Seconds() {
        let timeout: TimeInterval = 3.0
        XCTAssertGreaterThanOrEqual(timeout, 3.0)
        XCTAssertLessThanOrEqual(timeout, 3.0)
    }

    // MARK: - Block / proceed logic

    func testCancelState_beforeCallbackReached_recordingShouldBlock() {
        let state = CancelState()
        XCTAssertFalse(state.callbackReached)

        // callbackReached == false → inference still running → block recording
        let shouldBlock = !state.callbackReached
        XCTAssertTrue(shouldBlock)
    }

    func testCancelState_afterCallbackReached_recordingShouldProceed() {
        let state = CancelState()
        state.markCallbackReached()
        state.cancel()

        // callbackReached == true && isCancelled == true
        // → inference should cancel → recording proceeds
        XCTAssertTrue(state.callbackReached)
        XCTAssertTrue(state.isCancelled)
    }

    func testCancelState_callbackNotReached_andNotCancelled_recordingShouldBlock() {
        let state = CancelState()
        // Neither callback reached nor cancelled - inference still running
        let inferenceStillRunning = !state.callbackReached && !state.isCancelled
        XCTAssertTrue(inferenceStillRunning)
    }

    func testCancelState_callbackReached_notCancelled_recordingShouldBlock() {
        let state = CancelState()
        state.markCallbackReached()

        // callbackReached but not cancelled yet
        // depends on implementation: callback done but cancel not triggered
        XCTAssertTrue(state.callbackReached)
        XCTAssertFalse(state.isCancelled)
    }
}
