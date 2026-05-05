package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.service.TranscriptionUploader
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
