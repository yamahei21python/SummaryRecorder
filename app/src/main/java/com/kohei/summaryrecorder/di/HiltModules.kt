package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.kohei.summaryrecorder.BuildConfig
import com.kohei.summaryrecorder.audio.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.audio.SummaryProvider
import com.kohei.summaryrecorder.audio.TranscriptionProvider
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.service.TranscriptionUploader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// ===== Qualifiers =====

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebugMode

// ChunkSizeを型で区別（qualifier不要）
data class ChunkSize(val bytes: Long)

// ===== Audio Provider Factory =====

class AudioProviderFactory @Inject constructor(
    @DebugMode private val debugMode: Boolean
) {
    fun create(context: Context): AudioProvider {
        return if (debugMode) {
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

// ===== Provider (debug/prod binding) =====

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideTranscriptionProvider(
        @DebugMode debugMode: Boolean,
        repository: TranscriptionRepository
    ): TranscriptionProvider {
        return if (debugMode) MockTranscriptionProvider() else repository
    }

    @Provides
    @Singleton
    fun provideSummaryProvider(
        @DebugMode debugMode: Boolean,
        repository: SummaryRepository
    ): SummaryProvider {
        return if (debugMode) MockSummaryProvider() else repository
    }

    @Provides
    @Singleton
    @DebugMode
    fun provideDebugMode(): Boolean = DebugConfig.debugMode

    @Provides
    @Singleton
    fun provideChunkSize(@DebugMode debugMode: Boolean): ChunkSize {
        return ChunkSize(
            bytes = if (debugMode) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
        )
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
}
