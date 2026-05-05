package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import com.kohei.summaryrecorder.service.ServiceRecordingController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideTranscriptionUploader(
        chunkRepository: ChunkRepository,
        transcriptionProvider: com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
    ): TranscriptionUploader {
        return TranscriptionUploader(chunkRepository, transcriptionProvider)
    }

    @Provides
    @Singleton
    fun provideSummarizeUseCase(
        chunkRepository: ChunkRepository,
        summaryRepo: SummaryProvider
    ): SummarizeUseCase {
        return SummarizeUseCase(chunkRepository, summaryRepo)
    }

    @Provides
    @Singleton
    fun provideRecordingController(
        impl: ServiceRecordingController
    ): RecordingController = impl
}
