package com.kohei.summaryrecorder.data.repository

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SummaryRepository(
    private val generativeModel: GenerativeModel,
    private val systemPrompt: String
) : SummaryProvider {

    companion object {
        private const val TIMEOUT_MS = 60_000L
        private val gson = Gson()
    }

    override suspend fun summarize(combinedText: String): Result<SummaryResult> {
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
            if (text.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Gemini returned empty response"))
            }
            parseSummaryResult(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * JSON文字列をSummaryResultにパース。
     * 失敗時はフォールバック: title=日時ベース自動生成, summaryText=生レスポンス。
     */
    private fun parseSummaryResult(json: String): Result<SummaryResult> {
        return try {
            val result = gson.fromJson(json, SummaryResult::class.java)
            if (result != null && result.title.isNotBlank() && result.summaryText.isNotBlank()) {
                Result.success(result)
            } else {
                Result.success(fallback(json))
            }
        } catch (e: JsonSyntaxException) {
            Result.success(fallback(json))
        }
    }

    private fun fallback(rawResponse: String): SummaryResult {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(Date())
        return SummaryResult(
            title = "${timestamp} 録音",
            summaryText = rawResponse.take(5000)
        )
    }
}
