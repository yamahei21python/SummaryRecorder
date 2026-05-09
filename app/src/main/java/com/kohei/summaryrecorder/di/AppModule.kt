package com.kohei.summaryrecorder.di

import android.content.Context
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.service.ServiceRecordingController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ===== Provider bindings =====

    @Provides
    @Singleton
    fun provideTranscriptionProvider(
        apiService: GroqApiService,
        dataStore: SettingsDataStore
    ): TranscriptionProvider = TranscriptionRepository(apiService, dataStore)

    @Provides
    @Singleton
    fun provideSummaryProvider(
        dataStore: SettingsDataStore,
        @ApplicationContext context: Context
    ): SummaryProvider = SummaryRepository(dataStore, context)

    @Provides
    @Singleton
    fun provideAudioProvider(): AudioProvider = RealAudioProvider()

    @Provides
    @Singleton
    fun provideRecordingController(
        impl: ServiceRecordingController
    ): RecordingController = impl
}
