package com.kohei.summaryrecorder.data.repository

import com.google.gson.Gson
import com.kohei.summaryrecorder.data.api.GroqTranscriptionResponse
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.kohei.summaryrecorder.data.api.GroqApiService
import java.io.File

class TranscriptionRepoTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: TranscriptionRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        val apiService = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)

        repository = TranscriptionRepository(
            apiService = apiService,
            apiKey = "test-api-key"
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transcribe returns text on success`() = runTest {
        val expectedText = "これはテストの文字起こし結果です。"
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = expectedText)))
                .setHeader("Content-Type", "application/json")
        )

        val tempFile = File.createTempFile("test_chunk", ".wav")
        tempFile.writeBytes(ByteArray(100)) // ダミーデータ

        val result = repository.transcribe(tempFile)

        assertTrue(result.isSuccess)
        assertEquals(expectedText, result.getOrThrow())

        tempFile.delete()
    }

    @Test
    fun `transcribe returns failure on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val tempFile = File.createTempFile("test_chunk", ".wav")
        tempFile.writeBytes(ByteArray(100))

        val result = repository.transcribe(tempFile)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())

        tempFile.delete()
    }

    @Test
    fun `transcribe returns failure on network error`() = runTest {
        // サーバーをシャットダウンしてネットワークエラーを模擬
        server.shutdown()

        // 再度サーバーを作り直さず、そのままリクエスト
        val apiService = Retrofit.Builder()
            .baseUrl("http://localhost:1/") // 存在しないポート
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)

        val repo = TranscriptionRepository(apiService = apiService, apiKey = "key")
        val tempFile = File.createTempFile("test_chunk", ".wav")
        tempFile.writeBytes(ByteArray(100))

        val result = repo.transcribe(tempFile)

        assertTrue(result.isFailure)

        tempFile.delete()
    }
}
