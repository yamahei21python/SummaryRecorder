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
import java.io.RandomAccessFile

class TranscriptionRepoSplitTest {

    private lateinit var server: MockWebServer
    private lateinit var apiService: GroqApiService
    private lateinit var mockDataStore: SettingsDataStore

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        mockDataStore = mockk()
        every { mockDataStore.groqApiKey } returns flowOf("test-key")
        coEvery { mockDataStore.getGroqApiKey() } returns "test-key"

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

    /**
     * テスト用WAVファイル作成（ヘッダー44byte + PCMデータ）
     */
    private fun createTestWav(pcmSizeBytes: Int): File {
        val file = File.createTempFile("test_split_", ".wav")
        RandomAccessFile(file, "rw").use { raf ->
            // RIFF header
            raf.write("RIFF".toByteArray())
            writeIntLE(raf, 36 + pcmSizeBytes)
            raf.write("WAVE".toByteArray())
            // fmt chunk
            raf.write("fmt ".toByteArray())
            writeIntLE(raf, 16)
            writeShortLE(raf, 1) // PCM
            writeShortLE(raf, 1) // mono
            writeIntLE(raf, 16000) // 16kHz
            writeIntLE(raf, 32000) // byte rate
            writeShortLE(raf, 2) // block align
            writeShortLE(raf, 16) // bits per sample
            // data chunk
            raf.write("data".toByteArray())
            writeIntLE(raf, pcmSizeBytes)
            // PCM data (silence)
            raf.write(ByteArray(pcmSizeBytes))
        }
        return file
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
        raf.writeByte((value shr 16) and 0xFF)
        raf.writeByte((value shr 24) and 0xFF)
    }

    private fun writeShortLE(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
    }

    @Test
    fun `small file is sent as single request`() = runTest {
        val smallChunkSize = 1000 // 1000 bytes max
        val repo = TranscriptionRepository(apiService, mockDataStore, maxChunkSize = smallChunkSize)

        // 100 bytes PCM = 144 bytes total (100 < 1000)
        val wavFile = createTestWav(100)

        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "小さいファイル")))
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.transcribe(wavFile)
        assertTrue(result.isSuccess)
        assertEquals("小さいファイル", result.getOrThrow())

        // 1リクエストのみ
        assertEquals(1, server.requestCount)

        wavFile.delete()
    }

    @Test
    fun `large file is split into multiple requests`() = runTest {
        // maxChunkSize = 200 bytes → ヘッダー44 + PCM 156 bytes per chunk
        val smallChunkSize = 200
        val repo = TranscriptionRepository(apiService, mockDataStore, maxChunkSize = smallChunkSize)

        // 300 bytes PCM = 344 bytes total (300 > 200 → split)
        val wavFile = createTestWav(300)

        // 2チャンクに分割される想定
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "前半")))
                .setHeader("Content-Type", "application/json")
        )
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "後半")))
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.transcribe(wavFile)
        assertTrue(result.isSuccess)
        assertEquals("前半\n\n後半", result.getOrThrow())

        // 2リクエスト
        assertEquals(2, server.requestCount)

        wavFile.delete()
    }

    @Test
    fun `split failure on second chunk returns error`() = runTest {
        val smallChunkSize = 200
        val repo = TranscriptionRepository(apiService, mockDataStore, maxChunkSize = smallChunkSize)

        val wavFile = createTestWav(300)

        // 1st chunk OK, 2nd chunk error
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "前半")))
                .setHeader("Content-Type", "application/json")
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.transcribe(wavFile)
        assertTrue(result.isFailure, "Should fail when second chunk fails")

        wavFile.delete()
    }

    @Test
    fun `temp chunk files are cleaned up on success`() = runTest {
        val smallChunkSize = 200
        val repo = TranscriptionRepository(apiService, mockDataStore, maxChunkSize = smallChunkSize)

        val wavFile = createTestWav(300)

        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "A")))
                .setHeader("Content-Type", "application/json")
        )
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(GroqTranscriptionResponse(text = "B")))
                .setHeader("Content-Type", "application/json")
        )

        // temp directory内のファイル数確認
        val tempDir = System.getProperty("java.io.tmpdir")
        val beforeCount = File(tempDir).listFiles { f -> f.name.startsWith("chunk_") }?.size ?: 0

        repo.transcribe(wavFile)

        val afterCount = File(tempDir).listFiles { f -> f.name.startsWith("chunk_") }?.size ?: 0
        assertEquals(beforeCount, afterCount, "Temp chunk files should be cleaned up")

        wavFile.delete()
    }
}
