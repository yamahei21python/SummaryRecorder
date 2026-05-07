import Foundation
@testable import SummaryRecorder

// MARK: - Mock Transcription Service
final class MockTranscriptionService: TranscriptionService, @unchecked Sendable {
    var mockResult: Result<String, Error> = .success("テスト文字起こし")
    var cancelStateCaptured: CancelState?
    var callCount = 0
    var capturedWavURL: URL?

    func transcribe(wavURL: URL, cancelState: CancelState) async throws -> String {
        callCount += 1
        capturedWavURL = wavURL
        cancelStateCaptured = cancelState
        return try mockResult.get()
    }
}

// MARK: - Mock Summarization Service
final class MockSummarizationService: SummarizationService, @unchecked Sendable {
    var mockResult: Result<SummaryOutput, Error> = .success(
        SummaryOutput(title: "テストタイトル", summaryText: "テスト要約")
    )
    var cancelStateCaptured: CancelState?
    var callCount = 0
    var capturedText: String?

    func summarize(text: String, cancelState: CancelState) async throws -> SummaryOutput {
        callCount += 1
        capturedText = text
        cancelStateCaptured = cancelState
        return try mockResult.get()
    }
}

// MARK: - Spy Session Repository
final class SpySessionRepository: SessionRepository, @unchecked Sendable {
    var savedSessions: [Session] = []
    var deletedSessions: [Session] = []
    var fetchAllCallCount = 0
    var fetchByIdCallCount = 0
    var mockFetchAllResult: [Session] = []
    var mockFetchByIdResult: Session?

    func fetchAll() async throws -> [Session] {
        fetchAllCallCount += 1
        return mockFetchAllResult
    }

    func fetch(by id: String) async throws -> Session? {
        fetchByIdCallCount += 1
        return mockFetchByIdResult
    }

    func save(_ session: Session) async throws {
        savedSessions.append(session)
    }

    func delete(_ session: Session) async throws {
        deletedSessions.append(session)
    }
}
