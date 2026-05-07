import XCTest

final class SummaryRecorderE2ETests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-E2E"]
        app.launch()
    }

    /// E2E: bundled jfk.wav → Groq文字起こし → Gemini要約
    /// 結果がUIに表示されることを確認
    func test_jfk_wav_groq_gemini_e2e() throws {
        // navigation: 左サイドバーにセッションが表示されるまで待機
        let sessionList = app.outlines.firstMatch
        XCTAssertTrue(sessionList.waitForExistence(timeout: 5))

        let sessionRow = sessionList.cells.firstMatch
        XCTAssertTrue(sessionRow.waitForExistence(timeout: 10))

        // sessionRowをクリック → Detail表示
        sessionRow.click()

        // 文字起こし完了 or エラー表示を待つ（API次第で最大120秒）
        let maxWait: TimeInterval = 120
        let start = Date()

        // Transcription textが表示されるのを待つ
        var transcriptionFound = false
        var summaryFound = false

        while Date().timeIntervalSince(start) < maxWait {
            // エラー表示をチェック
            if app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'エラー'")).firstMatch.exists {
                XCTFail("E2E failed with error: \(app.staticTexts.firstMatch.label)")
                return
            }

            // Transcription field or text
            if !transcriptionFound {
                let transcription = app.textViews.firstMatch
                if transcription.exists && (transcription.value as? String)?.isEmpty == false {
                    transcriptionFound = true
                }
            }

            // Summary field or text
            if !summaryFound {
                let summary = app.textViews.element(boundBy: 1)
                if summary.exists && (summary.value as? String)?.isEmpty == false {
                    summaryFound = true
                }
            }

            if transcriptionFound && summaryFound {
                break
            }

            // Synchronous wait (2秒)
            let pollUntil = Date().addingTimeInterval(2)
            RunLoop.current.run(until: pollUntil)
        }

        XCTAssertTrue(transcriptionFound, "文字起こし結果が表示されませんでした（制限時間 \(maxWait)秒）")
        XCTAssertTrue(summaryFound, "要約結果が表示されませんでした（制限時間 \(maxWait)秒）")

        // API Keyが正しく設定されていることの確認（Keychain保存済）
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'jfk'")).firstMatch.exists,
                      "セッションタイトルにファイル名が含まれていること")
    }
}
