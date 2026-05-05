package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.data.api.GroqApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGroqApiService(): GroqApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }
}
