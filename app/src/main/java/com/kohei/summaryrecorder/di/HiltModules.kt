package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.kohei.summaryrecorder.BuildConfig
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.service.ServiceRecordingController
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

// ===== Value types for DI (qualifier不要) =====

data class ChunkSize(val bytes: Long)
data class DebugMode(val enabled: Boolean)

// ===== Audio Provider Factory =====

class AudioProviderFactory @Inject constructor(
    private val debugMode: DebugMode
) {
    fun create(context: Context): AudioProvider {
        return if (debugMode.enabled) {
            DummyAudioProvider(inputStream = context.assets.open("dummy_audio.wav"))
        } else {
            RealAudioProvider()
        }
    }
}

// ===== Database =====

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideChunkDao(db: AppDatabase): ChunkDao {
        return db.chunkDao()
    }
}

// ===== Network =====

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGroqApiService(): GroqApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }
}

// ===== Repository =====

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
    fun provideSummaryRepository(): SummaryRepository {
        val model = GenerativeModel("gemini-2.0-flash", BuildConfig.GEMINI_API_KEY)
        return SummaryRepository(model)
    }
}

// ===== Config + Provider =====

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideDebugMode(): DebugMode = DebugMode(DebugConfig.debugMode)

    @Provides
    @Singleton
    fun provideChunkSize(debugMode: DebugMode): ChunkSize {
        return ChunkSize(
            bytes = if (debugMode.enabled) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
        )
    }

    @Provides
    @Singleton
    fun provideTranscriptionProvider(
        debugMode: DebugMode,
        repository: TranscriptionRepository
    ): TranscriptionProvider {
        return if (debugMode.enabled) MockTranscriptionProvider() else repository
    }

    @Provides
    @Singleton
    fun provideSummaryProvider(
        debugMode: DebugMode,
        repository: SummaryRepository
    ): SummaryProvider {
        return if (debugMode.enabled) MockSummaryProvider() else repository
    }
}

// ===== Use-case =====

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideTranscriptionUploader(
        dao: ChunkDao,
        transcriptionProvider: TranscriptionProvider
    ): TranscriptionUploader {
        return TranscriptionUploader(dao, transcriptionProvider)
    }

    @Provides
    @Singleton
    fun provideRecordingController(
        impl: ServiceRecordingController
    ): RecordingController = impl
}
