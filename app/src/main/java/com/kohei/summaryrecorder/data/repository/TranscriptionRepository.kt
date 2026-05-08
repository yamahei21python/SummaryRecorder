package com.kohei.summaryrecorder.data.repository

import com.kohei.summaryrecorder.data.api.GroqApiService
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import java.io.File
import java.io.RandomAccessFile

class TranscriptionRepository(
    private val apiService: GroqApiService,
    private val apiKey: String,
    private val maxChunkSize: Int = 25 * 1024 * 1024 // 25MB
) : TranscriptionProvider {

    companion object {
        private const val TAG = "TranscriptionRepo"
        private const val WAV_HEADER_SIZE = 44
    }

    override suspend fun transcribe(file: File): Result<String> {
        return try {
            if (file.length() <= maxChunkSize.toLong()) {
                transcribeSingleFile(file)
            } else {
                transcribeSplitFile(file)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun transcribeSingleFile(file: File): Result<String> {
        return try {
            Log.d(TAG, "transcribeSingleFile: ${file.name}, size=${file.length()}")
            val fileBody = file.asRequestBody("audio/wav".toMediaType())
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
            Log.d(TAG, "transcribeSingleFile: success, text='${response.text}'")
            Result.success(response.text)
        } catch (e: Exception) {
            val detail = if (e is retrofit2.HttpException) {
                val body = e.response()?.errorBody()?.string()
                "HTTP ${e.code()}: $body"
            } else e.message.orEmpty()
            Log.e(TAG, "transcribeSingleFile failed: $detail", e)
            Result.failure(Exception(detail, e))
        }
    }

    private suspend fun transcribeSplitFile(file: File): Result<String> {
        return try {
            val chunks = splitWavFile(file)
            val transcriptions = mutableListOf<String>()
            for (chunkFile in chunks) {
                val result = transcribeSingleFile(chunkFile)
                if (result.isFailure) {
                    chunks.forEach { if (it.exists()) it.delete() }
                    return result
                }
                transcriptions.add(result.getOrThrow())
                chunkFile.delete()
            }
            Result.success(transcriptions.joinToString("\n\n"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun splitWavFile(file: File): List<File> {
        val raf = RandomAccessFile(file, "r")
        val header = ByteArray(WAV_HEADER_SIZE)
        raf.readFully(header)
        val pcmDataSize = raf.length() - WAV_HEADER_SIZE
        val pcmPerChunk = maxChunkSize.toLong() - WAV_HEADER_SIZE

        val chunks = mutableListOf<File>()
        var offset = 0L
        var chunkIndex = 0

        while (offset < pcmDataSize) {
            val end = minOf(offset + pcmPerChunk, pcmDataSize)
            val chunkPcmSize = end - offset

            val chunkFile = File.createTempFile("chunk_${chunkIndex}_", ".wav")
            RandomAccessFile(chunkFile, "rw").use { chunkRaf ->
                // Copy header template and fix sizes
                chunkRaf.write(header)
                // Fix RIFF size
                chunkRaf.seek(4)
                val riffSize = (36 + chunkPcmSize).toInt()
                chunkRaf.writeByte(riffSize and 0xFF)
                chunkRaf.writeByte((riffSize shr 8) and 0xFF)
                chunkRaf.writeByte((riffSize shr 16) and 0xFF)
                chunkRaf.writeByte((riffSize shr 24) and 0xFF)
                // Fix data size
                chunkRaf.seek(40)
                chunkRaf.writeByte(chunkPcmSize.toInt() and 0xFF)
                chunkRaf.writeByte((chunkPcmSize.toInt() shr 8) and 0xFF)
                chunkRaf.writeByte((chunkPcmSize.toInt() shr 16) and 0xFF)
                chunkRaf.writeByte((chunkPcmSize.toInt() shr 24) and 0xFF)
                // Write PCM data
                chunkRaf.seek(WAV_HEADER_SIZE.toLong())
                raf.seek(WAV_HEADER_SIZE.toLong() + offset)
                val buffer = ByteArray(8192)
                var remaining = chunkPcmSize
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.readFully(buffer, 0, toRead)
                    chunkRaf.write(buffer, 0, toRead)
                    remaining -= toRead
                }
            }
            chunks.add(chunkFile)
            offset = end
            chunkIndex++
        }
        raf.close()
        return chunks
    }
}
