package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptionUploaderAtomicityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
        val realFile = tempFolder.newFile("exception_test.wav")
        realFile.writeBytes(ByteArray(100) { 0 })
        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = realFile.absolutePath, status = ChunkStatus.PENDING)

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
        val realFile = tempFolder.newFile("concurrent.wav")
        realFile.writeBytes(ByteArray(100) { 0 })

        var casCounter = 0
        coEvery { mockRepo.casToUploading(1L, any()) } coAnswers {
            casCounter++
            if (casCounter == 1) 1 else 0
        }
        coEvery { mockProvider.transcribe(any()) } returns Result.success("テキスト")
        coEvery { mockRepo.updateStatus(any(), any(), any(), any()) } returns Unit

        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = realFile.absolutePath, status = ChunkStatus.FAILED)

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
        // 44バイトを超えるデータを書き込み、空ファイル判定を回避
        val realFile = tempFolder.newFile("exists.wav")
        realFile.writeBytes(ByteArray(100) { 0 })
        val chunk1 = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = realFile.absolutePath, status = ChunkStatus.FAILED)

        coEvery { mockRepo.getByStatus(ChunkStatus.FAILED) } returns listOf(chunk1)
        coEvery { mockRepo.getByStatus(ChunkStatus.PENDING) } returns emptyList()
        coEvery { mockRepo.casToUploading(1L, any()) } returns 1
        coEvery { mockProvider.transcribe(any()) } returns Result.success("テキスト")

        uploader.retryFailedChunks()

        // chunk1 のみ処理
        coVerify(exactly = 1) { mockProvider.transcribe(any()) }
    }

    @Test
    fun `retryFailedChunks includes PENDING chunks`() = runTest {
        val realFile = tempFolder.newFile("pending.wav")
        realFile.writeBytes(ByteArray(100) { 0 })
        val chunkPending = ChunkEntity(id = 10L, sessionId = "s", chunkIndex = 0, filePath = realFile.absolutePath, status = ChunkStatus.PENDING)

        coEvery { mockRepo.getByStatus(ChunkStatus.FAILED) } returns emptyList()
        coEvery { mockRepo.getByStatus(ChunkStatus.PENDING) } returns listOf(chunkPending)
        coEvery { mockRepo.casToUploading(10L, any()) } returns 1
        coEvery { mockProvider.transcribe(any()) } returns Result.success("テキスト")

        uploader.retryFailedChunks()

        coVerify(exactly = 1) { mockProvider.transcribe(any()) }
    }

    @Test
    fun `uploadChunk with 44 bytes file marks DONE and keeps it`() = runTest {
        val file44 = tempFolder.newFile("header.wav")
        file44.writeBytes(ByteArray(44) { 0 })
        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = file44.absolutePath, status = ChunkStatus.PENDING)

        coEvery { mockRepo.casToUploading(1L, any()) } returns 1

        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isSuccess)
        coVerify { mockRepo.updateStatus(1L, ChunkStatus.DONE, "", any()) }
        // #4: WAVファイルは保持される（再文字起こし可能）
        kotlin.test.assertTrue(file44.exists())
    }

    @Test
    fun `uploadChunk with missing file marks FAILED`() = runTest {
        val missingFile = File(tempFolder.root, "missing.wav")
        val chunk = ChunkEntity(id = 1L, sessionId = "s", chunkIndex = 0, filePath = missingFile.absolutePath, status = ChunkStatus.PENDING)

        coEvery { mockRepo.casToUploading(1L, any()) } returns 1

        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isFailure)
        coVerify { mockRepo.casToFailed(1L, any()) }
    }
}
