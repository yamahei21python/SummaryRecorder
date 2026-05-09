package com.kohei.summaryrecorder.data.repository

import android.content.Context
import com.kohei.summaryrecorder.BuildConfig
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SummaryRepository(
    private val dataStore: SettingsDataStore,
    private val context: Context,
    private val modelFactory: (apiKey: String, systemInstruction: String) -> GenerativeModel = { apiKey, instruction ->
        GenerativeModel(
            modelName = "gemini-3.1-flash-lite-preview",
            apiKey = apiKey,
            systemInstruction = content {
                text(instruction)
                text("出力は必ずJSON形式で、\"title\"(20文字以内)と\"summaryText\"の2フィールドを含めること。")
            },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }
) : SummaryProvider {

    companion object {
        private const val TIMEOUT_MS = 60_000L
        private val gson = Gson()
    }

    private suspend fun resolveApiKey(): String {
        val fromStore = dataStore.getGeminiApiKey()
        return fromStore.ifEmpty { BuildConfig.GEMINI_API_KEY }
    }

    private suspend fun resolveInstruction(): String {
        val fromStore = dataStore.getSummaryInstruction()
        return fromStore.ifEmpty {
            try { context.getString(R.string.system_prompt_summary) }
            catch (_: Exception) { "あなたは議事録作成の専門家です。" }
        }
    }

    override suspend fun summarize(combinedText: String): Result<SummaryResult> {
        return try {
            val apiKey = resolveApiKey()
            val instruction = resolveInstruction()
            val model = modelFactory(apiKey, instruction)
            val response = withTimeout(TIMEOUT_MS) {
                model.generateContent(
                    content {
                        text(instruction)
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
