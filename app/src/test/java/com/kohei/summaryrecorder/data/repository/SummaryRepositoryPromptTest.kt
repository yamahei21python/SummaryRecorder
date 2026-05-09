package com.kohei.summaryrecorder.data.repository

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class SummaryRepositoryPromptTest {

    private lateinit var mockModel: GenerativeModel
    private lateinit var mockDataStore: SettingsDataStore
    private lateinit var mockContext: Context
    private lateinit var repository: SummaryRepository
    private val systemPrompt = "You are a helpful assistant."

    @Before
    fun setUp() {
        mockModel = mockk()
        mockDataStore = mockk()
        mockContext = mockk()

        every { mockDataStore.geminiApiKey } returns flowOf("")
        every { mockDataStore.summaryInstruction } returns flowOf(systemPrompt)
        coEvery { mockDataStore.getGeminiApiKey() } returns ""
        coEvery { mockDataStore.getSummaryInstruction() } returns systemPrompt

        // Context is still used as fallback when DataStore returns empty
        // Since we return systemPrompt from DataStore, context won't be called

        // Use modelFactory lambda to inject mock model
        repository = SummaryRepository(
            dataStore = mockDataStore,
            context = mockContext,
            modelFactory = { _, _ -> mockModel }
        )
    }

    @Test
    fun `summarize passes instruction and combined text to model`() = runTest {
        coEvery { mockModel.generateContent(any<Content>()) } returns mockk {
            every { text } returns """{"title":"Test","summaryText":"Summary"}"""
        }

        repository.summarize("Input Text")

        coVerify {
            mockModel.generateContent(match<Content> { content ->
                val textParts = content.parts.filterIsInstance<TextPart>()
                textParts.any { it.text.contains(systemPrompt) } &&
                textParts.any { it.text.contains("Input Text") }
            })
        }
    }
}
