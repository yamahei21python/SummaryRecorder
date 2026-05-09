package com.kohei.summaryrecorder.data.repository

import com.google.gson.Gson
import com.kohei.summaryrecorder.data.api.GroqTranscriptionResponse
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var apiService: GroqApiService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiService = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /** Helper: DataStore mock + TranscriptionRepository作成 */
    private fun createRepo(apiKey: String): Pair<TranscriptionRepository, SettingsDataStore> {
        val ds = mockk<SettingsDataStore>()
        every { ds.groqApiKey } returns flowOf(apiKey)
        coEvery { ds.getGroqApiKey() } returns apiKey
        return TranscriptionRepository(apiService, ds) to ds
    }

    @Test
    fun `transcribe returns text on success`() = runTest {
        val (repo, _) = createRepo("test-api-key")
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "成功")))
                .setHeader("Content-Type", "application/json")
        )
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        val result = repo.transcribe(f)
        assertTrue(result.isSuccess)
        assertEquals("成功", result.getOrThrow())
        f.delete()
    }

    @Test
    fun `transcribe returns failure on HTTP error`() = runTest {
        val (repo, _) = createRepo("test-api-key")
        server.enqueue(MockResponse().setResponseCode(401))
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        assertTrue(repo.transcribe(f).isFailure)
        f.delete()
    }

    @Test
    fun `transcribe uses custom api key in header`() = runTest {
        val (repo, ds) = createRepo("custom-key")
        server.enqueue(MockResponse().setBody("""{"text":"ok"}""").setHeader("Content-Type", "application/json"))
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        repo.transcribe(f)
        val req = server.takeRequest()
        assertEquals("Bearer custom-key", req.getHeader("Authorization"))
        f.delete()
    }

    @Test
    fun `transcribe switches api key dynamically`() = runTest {
        val (repo, ds) = createRepo("old-key")

        // 1st call with old-key
        server.enqueue(MockResponse().setBody("""{"text":"a"}""").setHeader("Content-Type", "application/json"))
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        repo.transcribe(f)
        assertEquals("Bearer old-key", server.takeRequest().getHeader("Authorization"))

        // Switch to new-key
        coEvery { ds.getGroqApiKey() } returns "new-key"
        server.enqueue(MockResponse().setBody("""{"text":"b"}""").setHeader("Content-Type", "application/json"))
        repo.transcribe(f)
        assertEquals("Bearer new-key", server.takeRequest().getHeader("Authorization"))

        f.delete()
    }

    @Test
    fun `wrong api key returns 401 failure`() = runTest {
        val (repo, _) = createRepo("wrong-key")
        server.enqueue(MockResponse().setResponseCode(401))
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        assertTrue(repo.transcribe(f).isFailure)
        f.delete()
    }

    @Test
    fun `empty key still sends request`() = runTest {
        val (repo, _) = createRepo("")
        server.enqueue(MockResponse().setBody("""{"text":"ok"}""").setHeader("Content-Type", "application/json"))
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        val result = repo.transcribe(f)
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertNotNull(req.getHeader("Authorization"))
        f.delete()
    }

    @Test
    fun `transcribe returns failure on network error`() = runTest {
        server.shutdown()
        val (repo, _) = createRepo("key")
        // 新規server不要: 停止済みURLでリトライ用に異なるapiService
        val deadApi = Retrofit.Builder()
            .baseUrl("http://localhost:1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
        val ds = mockk<SettingsDataStore>()
        every { ds.groqApiKey } returns flowOf("key")
        coEvery { ds.getGroqApiKey() } returns "key"
        val deadRepo = TranscriptionRepository(deadApi, ds)
        val f = File.createTempFile("tmp", ".wav").also { it.writeBytes(ByteArray(100)) }
        assertTrue(deadRepo.transcribe(f).isFailure)
        f.delete()
    }
}
