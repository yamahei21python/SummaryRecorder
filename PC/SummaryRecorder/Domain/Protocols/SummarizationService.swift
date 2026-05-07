import Foundation

protocol SummarizationService: Sendable {
    func summarize(text: String, cancelState: CancelState) async throws -> SummaryOutput
}
