package com.kohei.summaryrecorder.data.repository

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SummaryRepoTest {

    private lateinit var generativeModel: GenerativeModel
    private lateinit var repository: SummaryRepository

    // テスト用システムプロンプト
    private val testSystemPrompt = "テスト用システムプロンプト"

    @Before
    fun setUp() {
        generativeModel = mockk<GenerativeModel>()
        repository = SummaryRepository(generativeModel, testSystemPrompt)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `summarize success returns text`() = runTest {
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns "要約テキスト"
        coEvery { generativeModel.generateContent(any<Content>()) } returns mockResponse

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isSuccess)
        assertEquals("要約テキスト", result.getOrThrow())
        coVerify(exactly = 1) { generativeModel.generateContent(any<Content>()) }
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
            kotlinx.coroutines.delay(65_000L) // Delay longer than TIMEOUT_MS
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns "要約テキスト"
            mockResponse
        }

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
    }
}
