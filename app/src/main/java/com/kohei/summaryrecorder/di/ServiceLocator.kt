package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ServiceLocator {

    @Volatile
    private var _database: AppDatabase? = null

    @Volatile
    private var _groqApiKey: String? = null

    @Volatile
    private var _geminiApiKey: String? = null

    fun initialize(context: Context) {
        _database = AppDatabase.getInstance(context)
    }

    fun setApiKeys(groqKey: String, geminiKey: String) {
        _groqApiKey = groqKey
        _geminiApiKey = geminiKey
    }

    val database: AppDatabase
        get() = _database ?: error("ServiceLocator not initialized")

    val groqApiService: GroqApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }

    val transcriptionRepository: TranscriptionRepository by lazy {
        TranscriptionRepository(
            apiService = groqApiService,
            apiKey = _groqApiKey ?: error("Groq API key not set")
        )
    }

    val summaryRepository: SummaryRepository by lazy {
        val model = GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = _geminiApiKey ?: error("Gemini API key not set")
        )
        SummaryRepository(generativeModel = model)
    }
}
