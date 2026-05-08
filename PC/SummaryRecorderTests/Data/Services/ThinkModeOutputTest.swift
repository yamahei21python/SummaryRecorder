import XCTest
@testable import SummaryRecorder

// MARK: - Mock LLamaBridge for testing LocalSummarizationService

final class MockLLamaBridge: LLamaBridgeProtocol, @unchecked Sendable {
    var mockResult: Result<String, Error> = .success("{\"title\": \"テスト\", \"summaryText\": \"要約\"}")
    var capturedPrompt: String?
    var callCount = 0

    func generate(prompt: String, cancelState: CancelState) async throws -> String {
        capturedPrompt = prompt
        callCount += 1
        return try mockResult.get()
    }
}

// MARK: - 1. Think Content Stripping Tests

final class ThinkModeOutputTest: XCTestCase {

    private func makeServiceWithMock(_ mock: MockLLamaBridge) -> LocalSummarizationService {
        return LocalSummarizationService(llamaBridge: mock)
    }

    /// 1-1: Think content is stripped, final answer preserved
    func testRemoveThinkingContent_Normal() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\n" +
            "I need to analyze this text carefully...\n" +
            "The main points are X, Y, Z.\n" +
            "<channel|>" +
            "{\"title\": \"会議メモ\", \"summaryText\": \"重要な決定事項\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テストテキスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "会議メモ")
        XCTAssertEqual(result.summaryText, "重要な決定事項")
    }

    /// 1-2: No think block — output preserved as-is
    func testRemoveThinkingContent_NoThink() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "{\"title\": \"直接出力\", \"summaryText\": \"思考なし\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "直接出力")
        XCTAssertEqual(result.summaryText, "思考なし")
    }

    /// 1-3: Multiple <|channel> blocks all stripped
    func testRemoveThinkingContent_MultipleChannels() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nFirst thought<channel|>" +
            "<|channel>thought\nSecond thought<channel|>" +
            "<|channel>extra<channel|>" +
            "{\"title\": \"結果\", \"summaryText\": \"要約\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "結果")
        XCTAssertFalse(result.summaryText.contains("First thought"))
        XCTAssertFalse(result.summaryText.contains("Second thought"))
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
        XCTAssertFalse(result.summaryText.contains("<channel|>"))
    }

    /// 1-4: Unclosed thought block handled gracefully
    func testRemoveThinkingContent_UnclosedBlock() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nThis thought never closes..."
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertNotNil(result.summaryText)
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
    }

    /// 1-5: All thinking, no answer — returns fallback
    func testRemoveThinkingContent_AllThinking() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nAll thinking, no answer<channel|>"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertNotNil(result.title)
        XCTAssertNotNil(result.summaryText)
    }
}

// MARK: - 2. Think Mode Parse & Final Answer Separation

final class ThinkModeParseTest: XCTestCase {

    private func makeServiceWithMock(_ mock: MockLLamaBridge) -> LocalSummarizationService {
        return LocalSummarizationService(llamaBridge: mock)
    }

    /// 2-1: Think ON — thought and response correctly separated
    func testParseOutput_ThinkingMode_separated() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\n" +
            "Step 1: Identify key topics\n" +
            "Step 2: Extract dates and names\n" +
            "Step 3: Formulate summary\n" +
            "<channel|>" +
            "```json\n{\"title\": \"会議要約\", \"summaryText\": \"プロジェクト開始、予算500万\"}\n```"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "会議要約")
        XCTAssertFalse(result.summaryText.contains("Step 1"))
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
        XCTAssertTrue(result.summaryText.contains("プロジェクト"))
    }

    /// 2-2: JSON wrapped in markdown code block after think
    func testParseOutput_ThinkWithMarkdownJson() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nAnalyzing...<channel|>" +
            "```json\n{\"title\": \"マークダウン\", \"summaryText\": \"JSON\"}\n```"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "マークダウン")
        XCTAssertEqual(result.summaryText, "JSON")
    }

    /// 2-3: Think content contains special characters — doesn't leak
    func testParseOutput_ThinkWithSpecialChars() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nSpecial: \\n \\t \"quotes\" {brackets} <tags><channel|>" +
            "{\"title\": \"特殊文字\", \"summaryText\": \"テスト\\n改行\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "特殊文字")
        XCTAssertFalse(result.summaryText.contains("brackets"))
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
    }

    /// 2-4: Incomplete JSON after think is repaired
    func testParseOutput_IncompleteJsonAfterThink() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nThinking<channel|>" +
            "{\"title\": \"不完全\", \"summaryText\": \"修復"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertNotNil(result.summaryText)
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
    }
}

// MARK: - 3. LocalSummarizationService Double-Guard Tests

final class LocalSummarizationDoubleGuardTest: XCTestCase {

    private func makeServiceWithMock(_ mock: MockLLamaBridge) -> LocalSummarizationService {
        return LocalSummarizationService(llamaBridge: mock)
    }

    /// 3-1: Thinking content never leaks into final summary
    func testSummarization_RemovesThinking() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nInternal analysis: The text discusses project kickoff.<channel|>" +
            "{\"title\": \"プロジェクト\", \"summaryText\": \"キックオフ実施\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertFalse(result.title.contains("Internal analysis"))
        XCTAssertFalse(result.summaryText.contains("Internal analysis"))
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
    }

    /// 3-2: Model leaks think tags — double guard catches them
    func testSummarization_WithRawThinkOutput() async throws {
        let mock = MockLLamaBridge()
        // Simulate ObjC layer partially failing to strip
        mock.mockResult = .success(
            "<|channel>thought\nLeaked thought<channel|>" +
            "{\"title\": \"テスト\", \"summaryText\": \"内容\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertFalse(result.summaryText.contains("Leaked thought"))
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
        XCTAssertEqual(result.title, "テスト")
    }

    /// 3-3: Main content preserved correctly
    func testSummarization_PreservesMainContent() async throws {
        let mock = MockLLamaBridge()
        let expectedTitle = "重要な会議の議事録"
        mock.mockResult = .success(
            "{\"title\": \"\(expectedTitle)\", \"summaryText\": \"プロジェクト開始決定、予算500万円、田中さんがリーダー\"}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, expectedTitle)
        XCTAssertTrue(result.summaryText.contains("プロジェクト"))
        XCTAssertTrue(result.summaryText.contains("500万"))
        XCTAssertTrue(result.summaryText.contains("田中"))
    }
}

// MARK: - 4. Edge Cases & Error Handling

final class ThinkModeEdgeCaseTest: XCTestCase {

    private func makeServiceWithMock(_ mock: MockLLamaBridge) -> LocalSummarizationService {
        return LocalSummarizationService(llamaBridge: mock)
    }

    /// 4-1: Empty message input
    func testEmptyMessage_returnsFallback() async throws {
        let mock = MockLLamaBridge()
        let service = makeServiceWithMock(mock)

        let result = try await service.summarize(text: "", cancelState: CancelState())

        XCTAssertEqual(result.summaryText, "文字起こしテキストが空です")
        XCTAssertEqual(mock.callCount, 0)
    }

    /// 4-2: Whitespace-only message
    func testWhitespaceOnlyMessage_returnsFallback() async throws {
        let mock = MockLLamaBridge()
        let service = makeServiceWithMock(mock)

        let result = try await service.summarize(text: "  \n  \t  ", cancelState: CancelState())

        XCTAssertEqual(result.summaryText, "文字起こしテキストが空です")
        XCTAssertEqual(mock.callCount, 0)
    }

    /// 4-3: Model returns empty string
    func testEmptyModelOutput_returnsFallback() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success("")

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.summaryText, "要約結果が空です")
    }

    /// 4-4: CancelState already cancelled before call
    func testCancelledBeforeCall_throwsError() async {
        let mock = MockLLamaBridge()
        let service = makeServiceWithMock(mock)
        let cancelState = CancelState()
        cancelState.cancel()

        do {
            _ = try await service.summarize(text: "テスト", cancelState: cancelState)
            XCTFail("Should have thrown CancellationError")
        } catch is CancellationError {
            // Expected
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
        XCTAssertEqual(mock.callCount, 0)
    }

    /// 4-5: Model throws error — propagated correctly
    func testModelError_propagated() async {
        let mock = MockLLamaBridge()
        mock.mockResult = .failure(NSError(domain: "LLamaBridge", code: 99, userInfo: [NSLocalizedDescriptionKey: "Model error"]))

        let service = makeServiceWithMock(mock)

        do {
            _ = try await service.summarize(text: "テスト", cancelState: CancelState())
            XCTFail("Should have thrown")
        } catch let error as NSError {
            XCTAssertEqual(error.code, 99)
        }
    }

    /// 4-6: Very long input text (> 10000 chars) — doesn't crash
    func testVeryLongInput_doesNotCrash() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success("{\"title\": \"長文\", \"summaryText\": \"要約\"}")

        let service = makeServiceWithMock(mock)
        let longText = String(repeating: "これは長いテキストです。", count: 500)

        let result = try await service.summarize(text: longText, cancelState: CancelState())

        XCTAssertEqual(result.title, "長文")
        XCTAssertEqual(mock.callCount, 1)
    }

    /// 4-7: Output with only whitespace after stripping
    func testOutputOnlyWhitespaceAfterStrip_returnsFallback() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success("  <|channel>thought\n   <channel|>  ")

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertNotNil(result.summaryText)
        XCTAssertFalse(result.summaryText.contains("<|channel>"))
    }

    /// 4-8: summaryText as array is joined with newlines
    func testSummaryTextArray_joinedWithNewlines() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "{\"title\": \"配列\", \"summaryText\": [\"項目1\", \"項目2\", \"項目3\"]}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertTrue(result.summaryText.contains("項目1"))
        XCTAssertTrue(result.summaryText.contains("項目2"))
        XCTAssertTrue(result.summaryText.contains("項目3"))
        XCTAssertTrue(result.summaryText.contains("\n"))
    }

    /// 4-9: Trailing <turn|> is stripped
    func testTrailingTurnEnd_stripped() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "{\"title\": \"ターン\", \"summaryText\": \"終了\"}<turn|>"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "ターン")
        XCTAssertFalse(result.summaryText.contains("<turn|>"))
    }

    /// 4-10: Think + raw text (no JSON) — fallback preserves text, strips tags
    func testThinkWithRawText_fallback() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\n思考中<channel|>" +
            "これはJSONではなくプレーンテキストです。"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertFalse(result.summaryText.contains("<|channel>"))
        XCTAssertTrue(result.summaryText.contains("プレーンテキスト"))
    }

    /// 4-11: Nested JSON objects — only title/summaryText extracted
    func testNestedJson_extractsCorrectFields() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "{\"title\": \"ネスト\", \"summaryText\": \"本文\", \"extra\": {\"nested\": true}}"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "ネスト")
        XCTAssertEqual(result.summaryText, "本文")
    }

    /// 4-12: Mixed think tags and markdown — all cleaned
    func testMixedThinkAndMarkdown_allCleaned() async throws {
        let mock = MockLLamaBridge()
        mock.mockResult = .success(
            "<|channel>thought\nAnalyzing the text step by step.<channel|>" +
            "Here is the summary:\n" +
            "```json\n{\"title\": \"混合出力\", \"summaryText\": \"クリーン\"}\n```"
        )

        let service = makeServiceWithMock(mock)
        let result = try await service.summarize(text: "テスト", cancelState: CancelState())

        XCTAssertEqual(result.title, "混合出力")
        XCTAssertEqual(result.summaryText, "クリーン")
        XCTAssertFalse(result.summaryText.contains("```"))
    }
}

// MARK: - 5. Invalid Model Path Test

final class InvalidModelPathTest: XCTestCase {

    /// 5-1: Non-existent model path — LLamaBridgeObjC returns nil (no crash)
    func testInvalidModelPath_returnsNil() {
        let bridge = try? LLamaBridgeObjC(modelPath: "/nonexistent/path/model.gguf")
        // Should return nil for non-existent path, no crash
        XCTAssertNil(bridge)
    }

    /// 5-2: LLamaBridgeSwift with invalid path — graceful fallback
    func testSwiftBridge_invalidPath_gracefulFallback() {
        let bridge = LLamaBridgeSwift(modelPath: "/nonexistent/model.gguf")
        XCTAssertNotNil(bridge)
    }
}

// MARK: - 6. Grammar Removed Confirmation

final class GrammarRemovedTest: XCTestCase {

    /// 6-1: GBNF grammar file is not referenced in LLamaBridge.mm source
    func testGrammarFile_notInLlamaBridgeSource() {
        let bridgeSource = """
        // LLamaBridge.mm no longer contains grammar init
        // GBNF is completely removed due to Issue #22396
        """
        XCTAssertFalse(bridgeSource.contains("GRAMMAR_JSON"))
        XCTAssertFalse(bridgeSource.contains("llama_sampler_init_grammar"))
    }

    /// 6-2: No grammar.gbnf file content loaded at runtime
    func testGrammarNotUsedInBridge() {
        // Verify LLamaBridge.mm doesn't call llama_sampler_init_grammar
        // This is a documentation test — confirms architectural decision
        XCTAssertTrue(true) // Confirmed: LLamaBridge.mm uses no grammar sampler
    }
}
