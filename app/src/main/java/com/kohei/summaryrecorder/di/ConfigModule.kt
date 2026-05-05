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

class DebugModeHolder {
    val isDebugMode: Boolean get() = DebugConfig.debugMode
}

data class ChunkSize(val bytes: Long)

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideDebugModeHolder(): DebugModeHolder = DebugModeHolder()

    @Provides
    @Singleton
    fun provideChunkSize(holder: DebugModeHolder): ChunkSize {
        return ChunkSize(
            bytes = if (holder.isDebugMode) DebugConfig.DEBUG_CHUNK_BYTES else DebugConfig.PRODUCTION_CHUNK_BYTES
        )
    }

    @Provides
    fun provideTranscriptionProvider(
        holder: DebugModeHolder,
        repository: TranscriptionRepository
    ): TranscriptionProvider {
        return if (holder.isDebugMode) MockTranscriptionProvider() else repository
    }

    @Provides
    fun provideSummaryProvider(
        holder: DebugModeHolder,
        repository: SummaryRepository
    ): SummaryProvider {
        return if (holder.isDebugMode) MockSummaryProvider() else repository
    }

    @Provides
    @Singleton
    fun provideAudioProvider(
        @ApplicationContext context: Context,
        holder: DebugModeHolder
    ): AudioProvider {
        return if (holder.isDebugMode) {
            DummyAudioProvider(inputStream = context.assets.open("dummy_audio.wav"))
        } else {
            com.kohei.summaryrecorder.audio.RealAudioProvider()
        }
    }

    @Provides
    @Singleton
    fun provideChunkRepository(impl: ChunkRepositoryImpl): ChunkRepository = impl
}
