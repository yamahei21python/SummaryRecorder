import XCTest
@testable import SummaryRecorder

final class CancelStateTest: XCTestCase {

    // MARK: - Initial state

    func testInitialNotCancelled() {
        let state = CancelState()
        XCTAssertFalse(state.isCancelled)
        XCTAssertFalse(state.callbackReached)
    }

    // MARK: - Cancel flag

    func testCancelSetsFlag() {
        let state = CancelState()
        state.cancel()
        XCTAssertTrue(state.isCancelled)
    }

    // MARK: - Callback flag

    func testMarkCallbackReached() {
        let state = CancelState()
        state.markCallbackReached()
        XCTAssertTrue(state.callbackReached)
    }

    // MARK: - Reset

    func testResetClearsFlags() {
        let state = CancelState()
        state.cancel()
        state.markCallbackReached()
        state.reset()
        XCTAssertFalse(state.isCancelled)
        XCTAssertFalse(state.callbackReached)
    }

    // MARK: - Sendable / concurrency

    func testCancelState_isSendable() {
        let state = CancelState()
        let expectation = expectation(description: "sendable")
        Task {
            state.cancel()
            XCTAssertTrue(state.isCancelled)
            expectation.fulfill()
        }
        waitForExpectations(timeout: 1.0)
    }

    func testConcurrentAccess_doesNotCrash() async {
        let state = CancelState()
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<100 {
                group.addTask {
                    state.cancel()
                    state.markCallbackReached()
                    _ = state.isCancelled
                    _ = state.callbackReached
                }
            }
        }
        // No crash = pass
    }

    // MARK: - Double cancel idempotent

    func testCancel_idempotent() {
        let state = CancelState()
        state.cancel()
        state.cancel()
        XCTAssertTrue(state.isCancelled)
    }

    func testMarkCallback_idempotent() {
        let state = CancelState()
        state.markCallbackReached()
        state.markCallbackReached()
        XCTAssertTrue(state.callbackReached)
    }
}
