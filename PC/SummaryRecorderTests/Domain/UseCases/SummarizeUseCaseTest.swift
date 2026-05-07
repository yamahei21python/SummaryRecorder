import XCTest
@testable import SummaryRecorder

final class SummarizeUseCaseTest: XCTestCase {
    private var mockService: MockSummarizationService!
    private var spyRepository: SpySessionRepository!
    private var sut: SummarizeUseCase!

    override func setUp() {
        super.setUp()
        mockService = MockSummarizationService()
        spyRepository = SpySessionRepository()
        sut = SummarizeUseCase(summarizationService: mockService, sessionRepository: spyRepository)
    }

    override func tearDown() {
        mockService = nil
        spyRepository = nil
        sut = nil
        super.tearDown()
    }

    // MARK: - Local Mode (transcriptionText direct)

    func testLocalMode_usesTranscriptionText() async throws {
        let session = Session(
            transcriptionText: "会議の議事録です",
            status: SessionStatus.recorded.rawValue
        )
        mockService.mockResult = .success(SummaryOutput(title: "会議", summaryText: "議事録の要約"))

        let result = try await sut.execute(session: session, cancelState: CancelState())

        XCTAssertEqual(mockService.capturedText, "会議の議事録です")
        XCTAssertEqual(result.title, "会議")
    }

    func testLocalMode_emptyTranscriptionText_throwsError() async {
        let session = Session(
            transcriptionText: "",
            status: SessionStatus.recorded.rawValue
        )

        do {
            _ = try await sut.execute(session: session, cancelState: CancelState())
            XCTFail("Should throw")
        } catch let error as SummarizeError {
            XCTAssertEqual(error, SummarizeError.noTranscriptionText)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    // MARK: - Groq Mode (chunks)

    func testGroqMode_joinsChunkTexts() async throws {
        let session = Session(status: SessionStatus.recorded.rawValue)
        let chunk0 = Chunk(chunkIndex: 0, status: ChunkStatus.done.rawValue, transcriptionText: "最初の部分")
        let chunk1 = Chunk(chunkIndex: 1, status: ChunkStatus.done.rawValue, transcriptionText: "次の部分")
        let chunk2 = Chunk(chunkIndex: 2, status: ChunkStatus.done.rawValue, transcriptionText: "最後の部分")
        session.chunks = [chunk0, chunk1, chunk2]

        mockService.mockResult = .success(SummaryOutput(title: "t", summaryText: "s"))
        _ = try await sut.execute(session: session, cancelState: CancelState())

        XCTAssertEqual(mockService.capturedText, "最初の部分\n\n次の部分\n\n最後の部分")
    }

    func testGroqMode_ordersChunksByIndex() async throws {
        let session = Session(status: SessionStatus.recorded.rawValue)
        let chunk2 = Chunk(chunkIndex: 2, status: ChunkStatus.done.rawValue, transcriptionText: "C")
        let chunk0 = Chunk(chunkIndex: 0, status: ChunkStatus.done.rawValue, transcriptionText: "A")
        let chunk1 = Chunk(chunkIndex: 1, status: ChunkStatus.done.rawValue, transcriptionText: "B")
        session.chunks = [chunk2, chunk0, chunk1]

        mockService.mockResult = .success(SummaryOutput(title: "t", summaryText: "s"))
        _ = try await sut.execute(session: session, cancelState: CancelState())

        XCTAssertEqual(mockService.capturedText, "A\n\nB\n\nC")
    }

    func testGroqMode_emptyChunks_throwsError() async {
        let session = Session(transcriptionText: "", status: SessionStatus.recorded.rawValue)

        do {
            _ = try await sut.execute(session: session, cancelState: CancelState())
            XCTFail("Should throw")
        } catch let error as SummarizeError {
            XCTAssertEqual(error, SummarizeError.noTranscriptionText)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testGroqMode_allFailedChunks_addsWarning() async {
        let session = Session(transcriptionText: "", status: SessionStatus.recorded.rawValue)
        let failedChunk = Chunk(chunkIndex: 0, status: ChunkStatus.failed.rawValue)
        session.chunks = [failedChunk]

        do {
            _ = try await sut.execute(session: session, cancelState: CancelState())
            XCTFail("Should throw")
        } catch let error as SummarizeError {
            if case .allChunksFailed(let count) = error {
                XCTAssertEqual(count, 1)
            } else {
                XCTFail("Expected allChunksFailed")
            }
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testGroqMode_filtersOnlyDoneChunks() async throws {
        let session = Session(status: SessionStatus.recorded.rawValue)
        session.chunks = [
            Chunk(chunkIndex: 0, status: ChunkStatus.done.rawValue, transcriptionText: "OK"),
            Chunk(chunkIndex: 1, status: ChunkStatus.failed.rawValue),
            Chunk(chunkIndex: 2, status: ChunkStatus.done.rawValue, transcriptionText: "もOK")
        ]

        mockService.mockResult = .success(SummaryOutput(title: "t", summaryText: "s"))
        _ = try await sut.execute(session: session, cancelState: CancelState())

        XCTAssertEqual(mockService.capturedText, "OK\n\nもOK")
    }

    // MARK: - Double Summarize Prevention

    func testAlreadySummarizing_returnsEarly() async throws {
        let session = Session(
            transcriptionText: "テキスト",
            status: SessionStatus.summarizing.rawValue
        )

        let result = try await sut.execute(session: session, cancelState: CancelState())
        XCTAssertEqual(mockService.callCount, 0)
    }
}
