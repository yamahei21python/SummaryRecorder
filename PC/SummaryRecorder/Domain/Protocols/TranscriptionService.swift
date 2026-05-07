import Foundation

struct SummaryOutput: Sendable {
    let title: String
    let summaryText: String
}

protocol TranscriptionService: Sendable {
    func transcribe(wavURL: URL, cancelState: CancelState) async throws -> String
}
