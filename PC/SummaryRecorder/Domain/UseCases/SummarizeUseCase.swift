import Foundation

enum SummarizeError: Error, Equatable, LocalizedError {
    case noTranscriptionText
    case allChunksFailed(Int)

    var errorDescription: String? {
        switch self {
        case .noTranscriptionText: "No transcription text available"
        case .allChunksFailed(let count): "All \(count) chunks failed transcription"
        }
    }
}

struct SummarizeUseCase: Sendable {
    let summarizationService: SummarizationService
    let sessionRepository: (any SessionRepository)?

    init(summarizationService: SummarizationService,
         sessionRepository: (any SessionRepository)? = nil) {
        self.summarizationService = summarizationService
        self.sessionRepository = sessionRepository
    }

    func execute(session: Session, cancelState: CancelState) async throws -> SummaryOutput {
        guard session.status != SessionStatus.summarizing.rawValue else {
            return SummaryOutput(title: "", summaryText: "")
        }

        let text = try buildTranscriptionText(session: session)

        return try await summarizationService.summarize(text: text, cancelState: cancelState)
    }

    // MARK: - Private

    private func buildTranscriptionText(session: Session) throws -> String {
        // Prefer direct transcriptionText
        if !session.transcriptionText.isEmpty {
            return session.transcriptionText
        }

        // Fall back to chunk texts
        guard !session.chunks.isEmpty else {
            throw SummarizeError.noTranscriptionText
        }

        let doneChunks = session.chunks
            .filter { $0.status == ChunkStatus.done.rawValue }
            .sorted { $0.chunkIndex < $1.chunkIndex }

        if doneChunks.isEmpty {
            throw SummarizeError.allChunksFailed(session.chunks.count)
        }

        let text = doneChunks.compactMap { $0.transcriptionText }.joined(separator: "\n\n")

        guard !text.isEmpty else {
            throw SummarizeError.noTranscriptionText
        }

        return text
    }
}
