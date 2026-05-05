package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.data.model.SummaryResult
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
import java.io.File
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
            if (DebugConfig.debugMode) DebugConfig.DEBUG_CHUNK_BYTES
            else DebugConfig.PRODUCTION_CHUNK_BYTES
        })
    }

    // Hilt生成タイミング（アプリ起動時）にDebugConfig.debugModeはまだfalse。
    // Lazy wrapperで呼び出し時に評価する。
    @Provides
    fun provideTranscriptionProvider(
        repository: TranscriptionRepository
    ): TranscriptionProvider = LazyTranscriptionProvider(repository)

    @Provides
    fun provideSummaryProvider(
        repository: SummaryRepository
    ): SummaryProvider = LazySummaryProvider(repository)

    @Provides
    fun provideAudioProvider(
        @ApplicationContext context: Context
    ): AudioProvider = LazyAudioProvider(context)

    @Provides
    @Singleton
    fun provideChunkRepository(impl: ChunkRepositoryImpl): ChunkRepository = impl
}

// ===== Lazy Wrappers: 呼び出し時にDebugConfig.debugModeを評価 =====

private class LazyTranscriptionProvider(
    private val real: TranscriptionRepository
) : TranscriptionProvider {
    override suspend fun transcribe(file: File): Result<String> {
        return if (DebugConfig.debugMode) MockTranscriptionProvider().transcribe(file)
        else real.transcribe(file)
    }
}

private class LazySummaryProvider(
    private val real: SummaryRepository
) : SummaryProvider {
    override suspend fun summarize(text: String): Result<SummaryResult> {
        return if (DebugConfig.debugMode) MockSummaryProvider().summarize(text)
        else real.summarize(text)
    }
}

private class LazyAudioProvider(
    private val context: Context
) : AudioProvider {

    private val dummy: DummyAudioProvider by lazy {
        DummyAudioProvider(inputStream = try {
            context.assets.open("dummy_audio.wav")
        } catch (e: Exception) {
            android.util.Log.w("ConfigModule", "dummy_audio.wav not found", e)
            byteArrayOf().inputStream()
        })
    }
    private val realAudio = RealAudioProvider()

    override fun start(): Boolean {
        return if (DebugConfig.debugMode) dummy.start() else realAudio.start()
    }

    override fun read(buffer: ShortArray, size: Int): Int {
        return if (DebugConfig.debugMode) dummy.read(buffer, size) else realAudio.read(buffer, size)
    }

    override fun stop() {
        if (DebugConfig.debugMode) dummy.stop() else realAudio.stop()
    }

    override fun release() {
        if (DebugConfig.debugMode) dummy.release() else realAudio.release()
    }
}
