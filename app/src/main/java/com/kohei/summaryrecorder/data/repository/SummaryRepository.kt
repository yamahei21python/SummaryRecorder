package com.kohei.summaryrecorder.data.repository

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import kotlinx.coroutines.withTimeout

class SummaryRepository(
    private val generativeModel: GenerativeModel
) : com.kohei.summaryrecorder.audio.SummaryProvider {

    companion object {
        private const val SYSTEM_PROMPT = """
あなたは議事録作成の専門家です。
以下の文字起こしテキストを要約してください。
出力形式:
1. 概要（3行以内）
2. 主要トピック（箇条書き）
3. 重要な発言・決定事項
4. アクションアイテム（あれば）
"""
        private const val TIMEOUT_MS = 60_000L // 60秒（長文要約用）
    }

    override suspend fun summarize(combinedText: String): Result<String> {
        return try {
            val response = withTimeout(TIMEOUT_MS) {
                generativeModel.generateContent(
                    content { text(combinedText) }
                )
            }
            val text = response.text
            if (text != null) {
                Result.success(text)
            } else {
                Result.failure(IllegalStateException("Gemini returned empty response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
