package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.audio.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.SummaryProvider
import com.kohei.summaryrecorder.audio.TranscriptionProvider
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
import javax.inject.Qualifier
import javax.inject.Singleton

// ===== Qualifiers =====

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebugMode

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChunkSizeBytes

// ===== Hilt Modules =====
// 全てServiceLocatorにデリゲート。Phase2でServiceLocator削除時に直接生成へ切替。

@Module
@InstallIn(SingletonComponent::class)
object HiltModules {

    @Provides
    @Singleton
    fun provideChunkDao(): ChunkDao {
        return ServiceLocator.database.chunkDao()
    }

    @Provides
    @Singleton
    fun provideTranscriptionProvider(): TranscriptionProvider {
        return ServiceLocator.transcriptionProvider
    }

    @Provides
    @Singleton
    fun provideSummaryProvider(): SummaryProvider {
        return ServiceLocator.summaryProvider
    }

    @Provides
    @Singleton
    fun provideTranscriptionRepository(): TranscriptionRepository {
        return ServiceLocator.transcriptionRepository
    }

    @Provides
    @Singleton
    fun provideSummaryRepository(): SummaryRepository {
        return ServiceLocator.summaryRepository
    }

    @Provides
    fun provideAudioProvider(@ApplicationContext context: Context): AudioProvider {
        return ServiceLocator.createAudioProvider(context)
    }

    @Provides
    @Singleton
    @DebugMode
    fun provideDebugMode(): Boolean = DebugConfig.debugMode

    @Provides
    @Singleton
    @ChunkSizeBytes
    fun provideChunkSizeBytes(@DebugMode debugMode: Boolean): Long {
        return if (debugMode) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
    }

    @Provides
    @Singleton
    fun provideTranscriptionUploader(
        dao: ChunkDao,
        transcriptionProvider: TranscriptionProvider
    ): TranscriptionUploader {
        return TranscriptionUploader(dao, transcriptionProvider)
    }
}
