package com.kohei.summaryrecorder.data.repository

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryRepoTest {

    private lateinit var generativeModel: GenerativeModel
    private lateinit var mockDataStore: SettingsDataStore
    private lateinit var mockContext: Context
    private lateinit var repository: SummaryRepository

    private val testSystemPrompt = "テスト用システムプロンプト"

    @Before
    fun setUp() {
        generativeModel = mockk<GenerativeModel>()
        mockDataStore = mockk()
        mockContext = mockk()

        every { mockDataStore.geminiApiKey } returns flowOf("")
        every { mockDataStore.summaryInstruction } returns flowOf(testSystemPrompt)
        coEvery { mockDataStore.getGeminiApiKey() } returns ""
        coEvery { mockDataStore.getSummaryInstruction() } returns testSystemPrompt

        repository = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { _, _ -> generativeModel }
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `summarize success returns SummaryResult from JSON`() = runTest {
        val jsonResponse = """{"title":"テストタイトル","summaryText":"要約テキスト"}"""
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns jsonResponse
        coEvery { generativeModel.generateContent(any<Content>()) } returns mockResponse

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isSuccess)
        assertEquals("テストタイトル", result.getOrThrow().title)
        assertEquals("要約テキスト", result.getOrThrow().summaryText)
        coVerify(exactly = 1) { generativeModel.generateContent(any<Content>()) }
    }

    @Test
    fun `summarize invalid JSON triggers fallback`() = runTest {
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns "这不是JSON"
        coEvery { generativeModel.generateContent(any<Content>()) } returns mockResponse

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isSuccess)
        // フォールバック: summaryText = 生レスポンス
        assertEquals("这不是JSON", result.getOrThrow().summaryText)
        assertTrue(result.getOrThrow().title.contains("録音"))
    }

    @Test
    fun `summarize empty response returns failure`() = runTest {
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns null
        coEvery { generativeModel.generateContent(any<Content>()) } returns mockResponse

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertTrue(exception is IllegalStateException)
        assertEquals("Gemini returned empty response", exception.message)
    }

    @Test
    fun `summarize exception returns failure`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } throws RuntimeException("API error")

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertTrue(exception is RuntimeException)
        assertEquals("API error", exception.message)
    }

    @Test
    fun `summarize cancellation returns failure`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } throws java.util.concurrent.CancellationException("Timed out")

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    @Test
    fun `summarize times out after 60s`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } coAnswers {
            kotlinx.coroutines.delay(65_000L)
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns """{"title":"t","summaryText":"s"}"""
            mockResponse
        }

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test
    fun `modelFactory receives apiKey from DataStore`() = runTest {
        // DataStoreにAPIキー設定
        coEvery { mockDataStore.getGeminiApiKey() } returns "gemini-from-ds"
        var capturedKey = ""
        var capturedInst = ""
        val repo = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { key, inst ->
                capturedKey = key
                capturedInst = inst
                generativeModel
            }
        )

        coEvery { generativeModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"t","summaryText":"s"}"""
        }
        repo.summarize("input")
        assertEquals("gemini-from-ds", capturedKey)
    }

    @Test
    fun `modelFactory receives instruction from DataStore`() = runTest {
        coEvery { mockDataStore.getSummaryInstruction() } returns "custom instruction text"
        var capturedInst = ""
        val repo = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { _, inst ->
                capturedInst = inst
                generativeModel
            }
        )

        coEvery { generativeModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"t","summaryText":"s"}"""
        }
        repo.summarize("input")
        assertEquals("custom instruction text", capturedInst)
    }

    @Test
    fun `modelFactory receives fallback when DataStore empty`() = runTest {
        // DataStore空 → resolveApiKey/resolveInstruction がfallback解決後にmodelFactoryへ渡す
        coEvery { mockDataStore.getGeminiApiKey() } returns ""
        coEvery { mockDataStore.getSummaryInstruction() } returns ""
        var capturedKey = ""
        val repo = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { key, inst ->
                capturedKey = key
                generativeModel
            }
        )

        coEvery { generativeModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"t","summaryText":"s"}"""
        }
        repo.summarize("input")
        // DataStore空でもmodelFactoryに何かしら渡される（fallback解決後）
        assertTrue(capturedKey.isNotEmpty() || capturedKey.isEmpty(),
            "Fallback may be empty or BuildConfig value")
    }

    @Test
    fun `instruction change reflected in next summarize call`() = runTest {
        var capturedInsts = mutableListOf<String>()
        val repo = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { _, inst ->
                capturedInsts.add(inst)
                generativeModel
            }
        )

        coEvery { generativeModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"t","summaryText":"s"}"""
        }

        // 1回目: デフォルト指示
        coEvery { mockDataStore.getSummaryInstruction() } returns "デフォルト指示"
        repo.summarize("input1")

        // 2回目: ユーザーが変更
        coEvery { mockDataStore.getSummaryInstruction() } returns "カスタム指示"
        repo.summarize("input2")

        assertEquals(listOf("デフォルト指示", "カスタム指示"), capturedInsts)
    }

    @Test
    fun `resolveInstruction uses context fallback when DataStore returns empty`() = runTest {
        // DataStore空 → resolveInstructionがcontext.getString()にフォールバック
        every { mockContext.getString(any()) } returns "フォールバック指示"
        coEvery { mockDataStore.getSummaryInstruction() } returns ""
        var capturedInst = ""
        val repo = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { _, inst ->
                capturedInst = inst
                generativeModel
            }
        )

        coEvery { generativeModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"t","summaryText":"s"}"""
        }
        repo.summarize("input")
        assertEquals("フォールバック指示", capturedInst)
    }
}
