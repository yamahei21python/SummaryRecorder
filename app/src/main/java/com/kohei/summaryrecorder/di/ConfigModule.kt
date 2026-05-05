package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ===== Value type for DI =====

data class ChunkSize(val bytes: Long)

// ===== Config + Provider（DebugConfig.debugMode を直接参照） =====

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideChunkSize(): ChunkSize {
        return ChunkSize(
            bytes = if (DebugConfig.debugMode) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
        )
    }

    @Provides
    @Singleton
    fun provideTranscriptionProvider(
        repository: TranscriptionRepository
    ): TranscriptionProvider {
        return if (DebugConfig.debugMode) MockTranscriptionProvider() else repository
    }

    @Provides
    @Singleton
    fun provideSummaryProvider(
        repository: SummaryRepository
    ): SummaryProvider {
        return if (DebugConfig.debugMode) MockSummaryProvider() else repository
    }
}
