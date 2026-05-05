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

/**
 * DebugConfig.debugMode を参照するプロキシークラス。
 * @Singleton で注入されるが、isDebugMode は毎回評価される。
 */
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
}
