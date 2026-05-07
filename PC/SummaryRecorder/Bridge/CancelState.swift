import Foundation
import os

final class CancelState: @unchecked Sendable {
    private let _isCancelled: OSAllocatedUnfairLock<Bool>
    private let _callbackReached: OSAllocatedUnfairLock<Bool>

    var isCancelled: Bool {
        get { _isCancelled.withLock { $0 } }
        set { _isCancelled.withLock { $0 = newValue } }
    }

    var callbackReached: Bool {
        get { _callbackReached.withLock { $0 } }
        set { _callbackReached.withLock { $0 = newValue } }
    }

    init(isCancelled: Bool = false, callbackReached: Bool = false) {
        _isCancelled = OSAllocatedUnfairLock(initialState: isCancelled)
        _callbackReached = OSAllocatedUnfairLock(initialState: callbackReached)
    }

    func cancel() {
        isCancelled = true
    }

    func markCallbackReached() {
        callbackReached = true
    }

    func reset() {
        _isCancelled.withLock { $0 = false }
        _callbackReached.withLock { $0 = false }
    }
}
