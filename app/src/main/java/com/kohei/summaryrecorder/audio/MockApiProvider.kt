package com.kohei.summaryrecorder.audio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.Closeable

/**
 * E2E用: MockWebServer でAPI応答を偽装。
 * debugMode=true 時に ServiceLocator 内で起動される。
 */
class MockApiProvider : Closeable {

    private val server = MockWebServer()

    /** 起動し、ベースURLを返す（例: http://127.0.0.1:PORT） */
    fun start(): String {
        // 文字起こしAPIの固定応答
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text": "これはテスト用の文字起こし結果です。"}""")
        )
        // 要約APIは Gemini SDK 経由のため MockWebServer では差し替えず、
        // SummaryProvider のモックで対応する。
        return server.url("").toString().trimEnd('/')
    }

    override fun close() {
        server.shutdown()
    }
}
