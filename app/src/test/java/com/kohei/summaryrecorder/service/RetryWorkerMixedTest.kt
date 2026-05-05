package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.service.TranscriptionUploader
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerMixedTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockProvider = mockk<TranscriptionProvider>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_mixed").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `FAILED and PENDING mixed - only FAILED retried`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "mixed-fp-session"

        val failedFile = File(tempDir, "failed.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = failedFile.absolutePath, status = ChunkStatus.FAILED
        ))

        val pendingFile = File(tempDir, "pending.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = pendingFile.absolutePath, status = ChunkStatus.PENDING
        ))

        uploader.retryFailedChunks()

        val all = chunkRepo.getBySession(sessionId)
        assertEquals(2, all.size)

        assertEquals(ChunkStatus.DONE, all[0].status)
        assertEquals("テキスト", all[0].transcriptionText)

        assertEquals(ChunkStatus.PENDING, all[1].status)
    }

    @Test
    fun `all FAILED files missing - entire session deleted`() = runTest {
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "all-missing-session"

        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/tmp/missing_0.wav", status = ChunkStatus.FAILED
        ))
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = "/tmp/missing_1.wav", status = ChunkStatus.FAILED
        ))
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 2,
            filePath = "/tmp/missing_2.wav", status = ChunkStatus.FAILED
        ))

        uploader.retryFailedChunks()

        assertEquals(0, chunkRepo.getBySession(sessionId).size)
    }

    @Test
    fun `UPLOADING chunks are not processed`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val uploadFile = File(tempDir, "uploading.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(ChunkEntity(
            sessionId = "upload-session", chunkIndex = 0,
            filePath = uploadFile.absolutePath, status = ChunkStatus.UPLOADING
        ))

        uploader.retryFailedChunks()

        val chunks = chunkRepo.getBySession("upload-session")
        assertEquals(1, chunks.size)
        assertEquals(ChunkStatus.UPLOADING, chunks[0].status)
    }

    @Test
    fun `multiple sessions - only one has missing files`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)

        val fileA = File(tempDir, "a.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(ChunkEntity(
            sessionId = "session-A", chunkIndex = 0,
            filePath = fileA.absolutePath, status = ChunkStatus.FAILED
        ))

        chunkRepo.insert(ChunkEntity(
            sessionId = "session-B", chunkIndex = 0,
            filePath = "/tmp/missing_b.wav", status = ChunkStatus.FAILED
        ))

        uploader.retryFailedChunks()

        val sessionA = chunkRepo.getBySession("session-A")
        assertEquals(1, sessionA.size)
        assertEquals(ChunkStatus.DONE, sessionA[0].status)

        assertEquals(0, chunkRepo.getBySession("session-B").size)
    }

    @Test
    fun `FAILED chunk with large file - retried successfully`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("大きなファイルテキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val largeFile = File(tempDir, "large.wav").also {
            it.writeBytes(ByteArray(1024 * 100))
        }
        chunkRepo.insert(ChunkEntity(
            sessionId = "large-session", chunkIndex = 0,
            filePath = largeFile.absolutePath, status = ChunkStatus.FAILED
        ))

        uploader.retryFailedChunks()

        val done = chunkRepo.getByStatus(ChunkStatus.DONE)
        assertEquals(1, done.size)
        assertEquals("大きなファイルテキスト", done[0].transcriptionText)
    }
}
