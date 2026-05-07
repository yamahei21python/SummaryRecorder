import XCTest
@testable import SummaryRecorder

final class SummarizationOutputParseTest: XCTestCase {
    func testParse_validJson() throws {
        let json = """
        {"title": "テストタイトル", "summaryText": "要約内容"}
        """
        let data = json.data(using: .utf8)!
        let parsed = try JSONSerialization.jsonObject(with: data) as! [String: String]

        XCTAssertEqual(parsed["title"], "テストタイトル")
        XCTAssertEqual(parsed["summaryText"], "要約内容")
    }

    func testParse_missingTitle_usesDateFallback() {
        let json = """
        {"summaryText": "要約のみ"}
        """
        let data = json.data(using: .utf8)!
        let parsed = try? JSONSerialization.jsonObject(with: data) as! [String: String]

        XCTAssertNil(parsed?["title"])
        XCTAssertNotNil(parsed?["summaryText"])
    }

    func testParse_invalidJson_fallback() {
        let json = "This is not JSON at all"
        let data = json.data(using: .utf8)!
        let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: String]

        XCTAssertNil(parsed)
    }

    func testParse_extraFields_ignored() throws {
        let json = """
        {"title": "t", "summaryText": "s", "extraField": "ignored", "anotherExtra": 123}
        """
        let data = json.data(using: .utf8)!
        let parsed = try JSONSerialization.jsonObject(with: data) as! [String: Any]

        XCTAssertEqual(parsed["title"] as? String, "t")
        XCTAssertEqual(parsed["summaryText"] as? String, "s")
    }

    func testParse_emptyStrings() throws {
        let json = """
        {"title": "", "summaryText": ""}
        """
        let data = json.data(using: .utf8)!
        let parsed = try JSONSerialization.jsonObject(with: data) as! [String: String]

        XCTAssertEqual(parsed["title"], "")
        XCTAssertEqual(parsed["summaryText"], "")
    }
}
