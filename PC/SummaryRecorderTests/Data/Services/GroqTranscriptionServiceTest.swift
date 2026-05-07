import XCTest
@testable import SummaryRecorder

final class GroqTranscriptionServiceTest: XCTestCase {
    private var tempDir: TempDirectory!

    override func setUp() {
        super.setUp()
        tempDir = TempDirectory()
    }

    override func tearDown() {
        MockURLProtocol.clear()
        tempDir.cleanup()
        super.tearDown()
    }

    func testTranscription_sendsCorrectApiKey() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-api-key")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let body = #"{"text": "テスト結果"}"#.data(using: .utf8)!
            return (response, body)
        }

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)

        let service = GroqTranscriptionService(apiKey: "test-api-key", session: session)
        let wavURL = tempDir.writeWav(named: "test.wav", data: TestWavFactory.createWav(durationSeconds: 1))

        let result = try await service.transcribe(wavURL: wavURL, cancelState: CancelState())
        XCTAssertEqual(result, "テスト結果")

        MockURLProtocol.clear()
    }

    func testTranscription_handlesApiError() async {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)

        let service = GroqTranscriptionService(apiKey: "bad-key", session: session)
        let wavURL = tempDir.writeWav(named: "test.wav", data: TestWavFactory.createWav(durationSeconds: 1))

        do {
            _ = try await service.transcribe(wavURL: wavURL, cancelState: CancelState())
            XCTFail("Should throw")
        } catch let error as TranscriptionError {
            if case .apiError(let code, _) = error {
                XCTAssertEqual(code, 401)
            } else {
                XCTFail("Unexpected error type")
            }
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        MockURLProtocol.clear()
    }

    func testTranscription_smallFile_singleChunk() async throws {
        // Small file (<25MB) should be sent as single chunk
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let body = #"{"text": "result"}"#.data(using: .utf8)!
            return (response, body)
        }

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)

        let service = GroqTranscriptionService(apiKey: "key", session: session)
        let wavURL = tempDir.writeWav(named: "small.wav", data: TestWavFactory.createWav(durationSeconds: 1))

        let result = try await service.transcribe(wavURL: wavURL, cancelState: CancelState())
        XCTAssertEqual(result, "result")

        MockURLProtocol.clear()
    }
}
