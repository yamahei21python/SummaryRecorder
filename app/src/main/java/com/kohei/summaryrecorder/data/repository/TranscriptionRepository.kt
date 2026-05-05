package com.kohei.summaryrecorder.data.repository

import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import java.io.File

class TranscriptionRepository(
    private val apiService: GroqApiService,
    private val apiKey: String
) : TranscriptionProvider {

    override suspend fun transcribe(file: File): Result<String> {
        return try {
            val fileBody = file.asRequestBody(
                "audio/wav".toMediaType()
            )
            val filePart = MultipartBody.Part.createFormData(
                "file", file.name, fileBody
            )

            val response = apiService.transcribe(
                authorization = "Bearer $apiKey",
                file = filePart,
                model = "whisper-large-v3".toRequestBody("text/plain".toMediaType()),
                language = "ja".toRequestBody("text/plain".toMediaType()),
                responseFormat = "json".toRequestBody("text/plain".toMediaType())
            )

            Result.success(response.text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
