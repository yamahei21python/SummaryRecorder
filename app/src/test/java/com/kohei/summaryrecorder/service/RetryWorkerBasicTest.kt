package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerBasicTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockProvider = mockk<TranscriptionProvider>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_retry_basic").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `retry succeeds - FAILED to UPLOADING to DONE, file deleted`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("再送成功テキスト")

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "chunk_0.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(ChunkEntity(
            sessionId = "retry-session-001", chunkIndex = 0,
            filePath = chunkFile.absolutePath, status = ChunkStatus.FAILED
        ))

        uploader.retryFailedChunks()

        val chunks = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, chunks.size)
        assertEquals("再送成功テキスト", chunks[0].transcriptionText)
        assertFalse(chunkFile.exists())
    }

    @Test
    fun `retry fails - status stays FAILED, file preserved`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network timeout")
        )

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "chunk_1.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(ChunkEntity(
            sessionId = "retry-session-002", chunkIndex = 0,
            filePath = chunkFile.absolutePath, status = ChunkStatus.FAILED
        ))

        uploader.retryFailedChunks()

        assertEquals(1, dao.getByStatus(ChunkStatus.FAILED).size)
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `multiple FAILED chunks - all retried successfully`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val sessionId = "retry-session-003"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            dao.insert(ChunkEntity(
                sessionId = sessionId, chunkIndex = i,
                filePath = chunkFile.absolutePath, status = ChunkStatus.FAILED
            ))
        }

        uploader.retryFailedChunks()

        assertEquals(3, dao.getByStatus(ChunkStatus.DONE).size)
        assertEquals(0, dao.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `no FAILED chunks - no changes`() = runTest {
        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)

        dao.insert(ChunkEntity(
            sessionId = "done-only-session", chunkIndex = 0,
            filePath = "/tmp/dummy.wav", status = ChunkStatus.DONE,
            transcriptionText = "already done"
        ))

        uploader.retryFailedChunks()

        assertEquals(1, dao.getByStatus(ChunkStatus.DONE).size)
    }
}
