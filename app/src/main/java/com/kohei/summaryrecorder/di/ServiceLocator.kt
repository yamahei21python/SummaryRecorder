package com.kohei.summaryrecorder.di

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.kohei.summaryrecorder.audio.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.audio.SummaryProvider
import com.kohei.summaryrecorder.audio.TranscriptionProvider
import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ServiceLocator {

    // 本番用: lazy初期化
    private val _database: AppDatabase by lazy {
        AppDatabase.getInstance(contextHolder)
    }

    // テスト用: override可能
    @Volatile
    var testDatabase: AppDatabase? = null

    @Volatile
    var testTranscriptionRepo: TranscriptionRepository? = null

    @Volatile
    var testSummaryRepo: SummaryRepository? = null

    // モックProvider (debug mode)
    @Volatile
    var mockTranscriptionProvider: TranscriptionProvider? = null

    @Volatile
    var mockSummaryProvider: SummaryProvider? = null

    // Context保持用（initialize呼出前にlazy参照不能用）
    private lateinit var contextHolder: Context

    fun initialize(context: Context) {
        contextHolder = context.applicationContext
    }

    val database: AppDatabase
        get() = testDatabase ?: _database

    // ---- テスト用API ----

    fun setApiKeys(groqKey: String, geminiKey: String) {
        _groqApiKey = groqKey
        _geminiApiKey = geminiKey
    }

    // lazy 化された本実装（initialize 後しか使えない）
    private val _groqApiService: GroqApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }
    private lateinit var _groqApiKey: String
    private lateinit var _geminiApiKey: String

    val groqApiService: GroqApiService
        get() = _groqApiService

    val transcriptionRepository: TranscriptionRepository
        get() = testTranscriptionRepo ?: _lazyTranscriptionRepo

    private val _lazyTranscriptionRepo: TranscriptionRepository by lazy {
        TranscriptionRepository(
            apiService = groqApiService,
            apiKey = _groqApiKey
        )
    }

    val summaryRepository: SummaryRepository
        get() = testSummaryRepo ?: _lazySummaryRepo

    private val _lazySummaryRepo: SummaryRepository by lazy {
        val model = GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = _geminiApiKey
        )
        SummaryRepository(generativeModel = model)
    }

    // ===== Audio =====

    /**
     * 音源Provider。
     * debugMode=true → DummyAudioProvider（assets内WAV）
     * 本番 → RealAudioProvider（端末マイク）
     */
    fun createAudioProvider(context: Context): AudioProvider {
        if (DebugConfig.debugMode) {
            val stream = context.assets.open("dummy_audio.wav")
            return DummyAudioProvider(inputStream = stream)
        }
        return RealAudioProvider()
    }

    // ===== モックProvider (debug mode) =====

    /**
     * 文字起こしProvider。
     * debugMode=true → MockTranscriptionProvider
     * 本番 → ServiceLocator.transcriptionRepository
     */
    val transcriptionProvider: TranscriptionProvider
        get() = mockTranscriptionProvider
            ?: if (DebugConfig.debugMode) MockTranscriptionProvider()
            else transcriptionRepository

    /**
     * 要約Provider。
     * debugMode=true → MockSummaryProvider
     * 本番 → ServiceLocator.summaryRepository
     */
    val summaryProvider: SummaryProvider
        get() = mockSummaryProvider
            ?: if (DebugConfig.debugMode) MockSummaryProvider()
            else summaryRepository

    // ===== テスト用オーバーライド =====

    fun overrideForTest(
        database: AppDatabase,
        transcriptionRepository: TranscriptionRepository
    ) {
        testDatabase = database
        testTranscriptionRepo = transcriptionRepository
    }

    fun overrideSummaryRepository(summaryRepository: SummaryRepository) {
        testSummaryRepo = summaryRepository
    }

    fun clearTestOverrides() {
        testDatabase = null
        testTranscriptionRepo = null
        testSummaryRepo = null
    }
}
