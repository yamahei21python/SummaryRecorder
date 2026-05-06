package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.kohei.summaryrecorder.BuildConfig
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTranscriptionRepository(apiService: GroqApiService): TranscriptionRepository {
        return TranscriptionRepository(apiService, BuildConfig.GROQ_API_KEY)
    }

    @Provides
    @Singleton
    fun provideSummaryRepository(@ApplicationContext context: Context): SummaryRepository {
        // TODO: responseSchema未対応 (Gemini SDK 0.9.0にSchema/type APIなし)
        // JSON形式の構造化出力はresponseMimeType + systemInstructionで保証
        val config = generationConfig {
            responseMimeType = "application/json"
        }
        val promptText = try {
            context.getString(R.string.system_prompt_summary)
        } catch (_: Exception) {
            "あなたは議事録作成の専門家です。"
        }
        val model = GenerativeModel(
            modelName = "gemini-3.1-flash-lite-preview",
            apiKey = BuildConfig.GEMINI_API_KEY,
            systemInstruction = com.google.ai.client.generativeai.type.content {
                text(promptText)
                text("出力は必ずJSON形式で、\"title\"(20文字以内)と\"summaryText\"の2フィールドを含めること。")
            },
            generationConfig = config
        )
        return SummaryRepository(model, "")
    }
}
