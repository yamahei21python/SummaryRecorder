package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.di.ServiceLocator
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RetryWorker基本再送テスト（Phase 4）。
 *
 * 検証項目:
 * - FAILED→UPLOADING→DONE: 再送成功
 * - FAILED→UPLOADING→FAILED: 再送失敗
 * - 再送成功時: 音声ファイル削除
 * - 再送失敗時: 音声ファイル残存（次回再送用）
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerBasicTest {

    private lateinit var db: AppDatabase
    private lateinit var mockTranscriptionRepo: TranscriptionRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        mockTranscriptionRepo = mockk<TranscriptionRepository>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_retry_basic").also { it.mkdirs() }

        ServiceLocator.overrideForTest(
            database = db,
            transcriptionRepository = mockTranscriptionRepo
        )
    }

    @After
    fun tearDown() {
        ServiceLocator.clearTestOverrides()
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `retry succeeds - FAILED to UPLOADING to DONE, file deleted`() = runTest {
        // Arrange
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("再送成功テキスト")

        val dao = db.chunkDao()
        val chunkFile = File(tempDir, "chunk_0.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(
            ChunkEntity(
                sessionId = "retry-session-001",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        val result = worker.doWork()

        // Assert
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val chunks = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, chunks.size)
        assertEquals("再送成功テキスト", chunks[0].transcriptionText)
        assertEquals(ChunkStatus.DONE, chunks[0].status)

        // ファイル削除確認
        assertFalse(chunkFile.exists())
    }

    @Test
    fun `retry fails - status stays FAILED, file preserved`() = runTest {
        // Arrange
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network timeout")
        )

        val dao = db.chunkDao()
        val chunkFile = File(tempDir, "chunk_1.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(
            ChunkEntity(
                sessionId = "retry-session-002",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        val result = worker.doWork()

        // Assert
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val chunks = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(1, chunks.size)

        // ファイル残存（次回再送用）
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `multiple FAILED chunks - all retried successfully`() = runTest {
        // Arrange
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val sessionId = "retry-session-003"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            dao.insert(
                ChunkEntity(
                    sessionId = sessionId,
                    chunkIndex = i,
                    filePath = chunkFile.absolutePath,
                    status = ChunkStatus.FAILED
                )
            )
        }

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker.doWork()

        // Assert: 全チャンクDONE
        val doneChunks = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(3, doneChunks.size)
        assertEquals(0, dao.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `no FAILED chunks - doWork returns success with no changes`() = runTest {
        // Arrange: DONEチャンクのみ
        val dao = db.chunkDao()
        dao.insert(
            ChunkEntity(
                sessionId = "done-only-session",
                chunkIndex = 0,
                filePath = "/tmp/dummy.wav",
                status = ChunkStatus.DONE,
                transcriptionText = "already done"
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        val result = worker.doWork()

        // Assert: 何も変化なし
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(1, dao.getByStatus(ChunkStatus.DONE).size)
    }
}
