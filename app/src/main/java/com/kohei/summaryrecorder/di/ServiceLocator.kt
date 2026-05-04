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

    // 本番用: lazy初期化
    private val _database: AppDatabase by lazy {
        AppDatabase.getInstance(contextHolder)
    }

    // テスト用: override可能（lateinit var）
    @Volatile
    var testDatabase: AppDatabase? = null

    @Volatile
    var testTranscriptionRepo: TranscriptionRepository? = null

    @Volatile
    var testSummaryRepo: SummaryRepository? = null

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

    // ===== テスト用オーバーライド =====

    /**
     * テスト用: DB と TranscriptionRepository をオーバーライドする。
     * テストでのみ呼び出すこと。
     */
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

    /**
     * テスト用: オーバーライドをクリアする。
     * 各テストの @After で呼び出すこと。
     */
    fun clearTestOverrides() {
        testDatabase = null
        testTranscriptionRepo = null
        testSummaryRepo = null
    }
}
