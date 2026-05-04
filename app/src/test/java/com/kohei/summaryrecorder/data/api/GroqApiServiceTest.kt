package com.kohei.summaryrecorder.data.api

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GroqApiServiceTest {

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

    @Test
    fun `transcribe sends correct multipart request`() = runTest {
        // モックレスポンス
        val mockResponse = GroqTranscriptionResponse(text = "テスト文字起こし結果")
        server.enqueue(
            MockResponse()
                .setBody(Gson().toJson(mockResponse))
                .setHeader("Content-Type", "application/json")
        )

        // テスト用WAVファイル作成
        val tempFile = java.io.File.createTempFile("test", ".wav")
        tempFile.writeBytes(ByteArray(44))

        val result = apiService.transcribe(
            authorization = "Bearer test-key",
            file = okhttp3.MultipartBody.Part.createFormData(
                "file", "test.wav",
                tempFile.asRequestBody("audio/wav".toMediaType())
            ),
            model = "whisper-large-v3".toRequestBody("text/plain".toMediaType()),
            language = "ja".toRequestBody("text/plain".toMediaType()),
            responseFormat = "json".toRequestBody("text/plain".toMediaType())
        )

        // レスポンス検証
        assertEquals("テスト文字起こし結果", result.text)

        // リクエスト検証
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("openai/v1/audio/transcriptions"))
        assertTrue(request.getHeader("Authorization")!!.contains("Bearer test-key"))

        // multipart form-data確認
        val body = request.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3"))
        assertTrue(body.contains("ja"))

        tempFile.delete()
    }

    @Test
    fun `transcribe handles server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val tempFile = java.io.File.createTempFile("test", ".wav")
        tempFile.writeBytes(ByteArray(44))

        assertThrows(retrofit2.HttpException::class.java) {
            apiService.transcribe(
                authorization = "Bearer test-key",
                file = okhttp3.MultipartBody.Part.createFormData(
                    "file", "test.wav",
                    tempFile.asRequestBody("audio/wav".toMediaType())
                ),
                model = "whisper-large-v3".toRequestBody("text/plain".toMediaType()),
                language = "ja".toRequestBody("text/plain".toMediaType()),
                responseFormat = "json".toRequestBody("text/plain".toMediaType())
            )
        }

        tempFile.delete()
    }

    // --- helper extensions ---
    private fun java.io.File.asRequestBody(mediaType: okhttp3.MediaType) =
        okhttp3.RequestBody.Companion.asRequestBody(this, mediaType)

    private fun String.toRequestBody(mediaType: okhttp3.MediaType) =
        okhttp3.RequestBody.Companion.toRequestBody(this, mediaType)

    private fun String.toMediaType() = okhttp3.MediaType.Companion.toMediaType(this)
}
