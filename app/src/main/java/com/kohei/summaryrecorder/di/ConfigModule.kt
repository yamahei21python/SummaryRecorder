package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    fun provideTranscriptionProvider(
        repository: TranscriptionRepository
    ): TranscriptionProvider = repository

    @Provides
    fun provideSummaryProvider(
        repository: SummaryRepository
    ): SummaryProvider = repository

    @Provides
    fun provideAudioProvider(
        @ApplicationContext context: Context
    ): AudioProvider = RealAudioProvider()
}
