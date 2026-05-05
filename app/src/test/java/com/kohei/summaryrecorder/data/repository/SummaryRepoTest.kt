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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SummaryRepoTest {

    private lateinit var generativeModel: GenerativeModel
    private lateinit var repository: SummaryRepository

    // テスト用システムプロンプト
    private val testSystemPrompt = "テスト用システムプロンプト"

    @BeforeEach
    fun setUp() {
        generativeModel = mockk<GenerativeModel>()
        repository = SummaryRepository(generativeModel, testSystemPrompt)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("summarize success returns text")
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
    @DisplayName("summarize empty response returns failure")
    fun `summarize empty response returns failure`() = runTest {
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns null
        coEvery { generativeModel.generateContent(any<Content>()) } returns mockResponse

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertInstanceOf(IllegalStateException::class.java, exception)
        assertEquals("Gemini returned empty response", exception.message)
    }

    @Test
    @DisplayName("summarize exception returns failure")
    fun `summarize exception returns failure`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } throws RuntimeException("API error")

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertInstanceOf(RuntimeException::class.java, exception)
        assertEquals("API error", exception.message)
    }

    @Test
    @DisplayName("summarize cancellation returns failure")
    fun `summarize cancellation returns failure`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } throws java.util.concurrent.CancellationException("Timed out")

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        assertInstanceOf(java.util.concurrent.CancellationException::class.java, result.exceptionOrNull())
    }

    @Test
    @DisplayName("summarize times out after 60s")
    fun `summarize times out after 60s`() = runTest {
        coEvery { generativeModel.generateContent(any<Content>()) } coAnswers {
            kotlinx.coroutines.delay(65_000L) // Delay longer than TIMEOUT_MS
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns "要約テキスト"
            mockResponse
        }

        val result = repository.summarize("テスト入力テキスト")

        assertTrue(result.isFailure)
        assertInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java, result.exceptionOrNull())
    }
}
