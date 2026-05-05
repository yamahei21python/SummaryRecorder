package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.kohei.summaryrecorder.BuildConfig
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.db.ChunkDao
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
        val model = GenerativeModel("gemini-2.0-flash", BuildConfig.GEMINI_API_KEY)
        return SummaryRepository(model, context.getString(R.string.system_prompt_summary))
    }
}
