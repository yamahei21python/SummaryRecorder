package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.provider.SummaryProvider

/** E2E用: 固定要約結果を返すモック */
class MockSummaryProvider : SummaryProvider {
    override suspend fun summarize(text: String): Result<String> =
        Result.success("【E2Eテスト要約】\n1. 概要: テスト用音声\n2. トピック: サンプル\n3. 決定事項: なし\n4. アクション: なし")
}
