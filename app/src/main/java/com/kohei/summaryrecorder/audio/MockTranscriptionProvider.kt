package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
import java.io.File

/** E2E用: 固定文字起こし結果を返すモック */
class MockTranscriptionProvider : TranscriptionProvider {
    override suspend fun transcribe(file: File): Result<String> =
        Result.success("これはテスト用の文字起こし結果です。")
}
