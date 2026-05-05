package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptionUploaderAtomicityTest {

    private lateinit var mockRepo: ChunkRepository
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var uploader: TranscriptionUploader

    @Before
    fun setUp() {
        mockRepo = mockk(relaxed = true)
        mockProvider = mockk()
        uploader = TranscriptionUploader(mockRepo, mockProvider)
    }

    @Test
    fun `transcribe exception after UPLOADING status properly reverts to FAILED`() = runTest {
        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = "/path.wav", status = ChunkStatus.PENDING)

        // casToUploading は成功(1)を返す
        coEvery { mockRepo.casToUploading(1L, any()) } returns 1
        coEvery { mockProvider.transcribe(any()) } throws RuntimeException("Network Error")

        uploader.uploadChunk(chunk)

        // casToUploading → transcribe例外 → casToFailed
        coVerify(ordering = Ordering.SEQUENCE) {
            mockRepo.casToUploading(1L, any())
            mockRepo.casToFailed(1L, any())
        }
    }

    @Test
    fun `uploadChunk skips if already UPLOADING - casToUploading returns 0`() = runTest {
        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = "/path.wav", status = ChunkStatus.UPLOADING)

        // casToUploading が 0（既に他で処理中）
        coEvery { mockRepo.casToUploading(1L, any()) } returns 0

        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { mockProvider.transcribe(any()) }
    }

    @Test
    fun `uploadChunk skips if already DONE - casToUploading returns 0`() = runTest {
        val chunk = ChunkEntity(id = 2L, sessionId = "s", chunkIndex = 0, filePath = "/path.wav", status = ChunkStatus.DONE)

        coEvery { mockRepo.casToUploading(2L, any()) } returns 0

        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { mockProvider.transcribe(any()) }
    }

    @Test
    fun `concurrent upload same chunk - only one succeeds`() = runTest {
        var casCounter = 0
        coEvery { mockRepo.casToUploading(1L, any()) } coAnswers {
            casCounter++
            if (casCounter == 1) 1 else 0
        }
        coEvery { mockProvider.transcribe(any()) } returns Result.success("テキスト")
        coEvery { mockRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = "/path.wav", status = ChunkStatus.FAILED)

        val results = mutableListOf<Result<String>>()
        coroutineScope {
            launch { results.add(uploader.uploadChunk(chunk)) }
            launch { results.add(uploader.uploadChunk(chunk)) }
        }

        // transcribe は1回のみ呼ばれる
        coVerify(exactly = 1) { mockProvider.transcribe(any()) }
        // 1件成功・1件失敗
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
    }

    @Test
    fun `retryFailedChunks skips already processing chunks`() = runTest {
        // chunk1: FAILED, chunk2: UPLOADING (getByStatus は FAILED のみ返す)
        val chunk1 = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = "/exists.wav", status = ChunkStatus.FAILED)

        coEvery { mockRepo.getByStatus(ChunkStatus.FAILED) } returns listOf(chunk1)
        coEvery { mockRepo.casToUploading(1L, any()) } returns 1
        coEvery { mockProvider.transcribe(any()) } returns Result.success("テキスト")

        uploader.retryFailedChunks()

        // chunk1 のみ処理
        coVerify(exactly = 1) { mockProvider.transcribe(any()) }
    }
}
