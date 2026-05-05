package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


class ChunkSize(private val debugModeProvider: () -> Boolean) {
    val bytes: Long
        get() = if (debugModeProvider()) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
}

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideChunkSize(): ChunkSize {
        return ChunkSize(debugModeProvider = { DebugConfig.debugMode })
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

    @Provides
    @Singleton
    fun provideAudioProvider(
        @ApplicationContext context: Context
    ): AudioProvider {
        return if (DebugConfig.debugMode) {
            DummyAudioProvider(inputStream = context.assets.open("dummy_audio.wav"))
        } else {
            com.kohei.summaryrecorder.audio.RealAudioProvider()
        }
    }

    @Provides
    @Singleton
    fun provideChunkRepository(impl: ChunkRepositoryImpl): ChunkRepository = impl
}
