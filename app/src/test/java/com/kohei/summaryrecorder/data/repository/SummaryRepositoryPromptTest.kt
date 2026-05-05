package com.kohei.summaryrecorder.data.repository

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class SummaryRepositoryPromptTest {

    private lateinit var mockModel: GenerativeModel
    private lateinit var repository: SummaryRepository
    private val systemPrompt = "You are a helpful assistant."

    @Before
    fun setUp() {
        mockModel = mockk()
        repository = SummaryRepository(mockModel, systemPrompt)
    }

    @Test
    fun `summarize includes system prompt and combined text in API call`() = runTest {
        coEvery { mockModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns "Summary Result"
        }

        repository.summarize("Input Text")

        coVerify {
            mockModel.generateContent(match { content ->
                // システムプロンプトと入力テキストの両方が含まれていることを確認する
                // 実装によっては Content 内に複数の Part があるか、結合されているかを確認
                val textParts = content.parts.filterIsInstance<TextPart>()
                textParts.any { it.text.contains(systemPrompt) } &&
                textParts.any { it.text.contains("Input Text") }
            })
        }
    }
}
