package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.domain.controller.RecordingController
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
        dao: ChunkDao,
        transcriptionProvider: com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
    ): TranscriptionUploader {
        return TranscriptionUploader(dao, transcriptionProvider)
    }

    @Provides
    @Singleton
    fun provideSummarizeUseCase(
        dao: ChunkDao,
        summaryRepo: SummaryRepository
    ): SummarizeUseCase {
        return SummarizeUseCase(dao, summaryRepo)
    }

    @Provides
    @Singleton
    fun provideRecordingController(
        impl: ServiceRecordingController
    ): RecordingController = impl
}
