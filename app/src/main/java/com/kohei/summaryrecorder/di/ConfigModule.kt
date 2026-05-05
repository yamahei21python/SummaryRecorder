package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


class ChunkSize(private val bytesProvider: () -> Long) {
    val bytes: Long
        get() = bytesProvider()
}

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideChunkSize(): ChunkSize {
        return ChunkSize(bytesProvider = {
            if (DebugConfig.debugMode) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
        })
    }

    @Provides
    fun provideTranscriptionProvider(
        repository: TranscriptionRepository
    ): TranscriptionProvider {
        return if (DebugConfig.debugMode) MockTranscriptionProvider() else repository
    }

    @Provides
    fun provideSummaryProvider(
        repository: SummaryRepository
    ): SummaryProvider {
        return if (DebugConfig.debugMode) MockSummaryProvider() else repository
    }

    @Provides
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
