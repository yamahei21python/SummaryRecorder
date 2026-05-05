package com.kohei.summaryrecorder.data.repository

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import kotlinx.coroutines.withTimeout

class SummaryRepository(
    private val generativeModel: GenerativeModel,
    private val systemPrompt: String
) : SummaryProvider {

    companion object {
        private const val TIMEOUT_MS = 60_000L // 60秒（長文要約用）
    }

    override suspend fun summarize(combinedText: String): Result<String> {
        return try {
            val response = withTimeout(TIMEOUT_MS) {
                generativeModel.generateContent(
                    content {
                        text(systemPrompt)
                        text(combinedText)
                    }
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
