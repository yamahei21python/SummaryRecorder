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
class RetryWorkerIdempotentTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        mockProvider = mockk<TranscriptionProvider>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_retry_idempotent").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `idempotent - second retry has no FAILED chunks`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "idempotent-session"

        for (i in 0..1) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            chunkRepo.insert(
                ChunkEntity(
                    sessionId = sessionId,
                    chunkIndex = i,
                    filePath = chunkFile.absolutePath,
                    status = ChunkStatus.FAILED
                )
            )
        }

        uploader.retryFailedChunks()

        assertEquals(2, chunkRepo.getByStatus(ChunkStatus.DONE).size)
        assertEquals(0, chunkRepo.getByStatus(ChunkStatus.FAILED).size)

        val count2 = uploader.retryFailedChunks()

        assertEquals(0, count2)
        assertEquals(2, chunkRepo.getByStatus(ChunkStatus.DONE).size)
        assertEquals(0, chunkRepo.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `file missing - deletes entire session`() = runTest {
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "missing-file-session"

        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = "/tmp/nonexistent_chunk_0.wav",
                status = ChunkStatus.FAILED
            )
        )
        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/nonexistent_chunk_1.wav",
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        assertEquals(0, chunkRepo.getBySession(sessionId).size)
        assertEquals(0, chunkRepo.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `file missing in one chunk - deletes only missing chunk`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "partial-missing-session"

        val existingFile = File(tempDir, "existing.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = existingFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )
        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/gone.wav",
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        val remaining = chunkRepo.getBySession(sessionId)
        assertEquals(1, remaining.size, "欠損チャンクのみ削除、健全チャンクは保持")
        assertEquals(ChunkStatus.DONE, remaining[0].status)
    }

    @Test
    fun `DONE and PENDING chunks are not processed`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "mixed-status-session"

        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = "/tmp/done.wav",
                status = ChunkStatus.DONE,
                transcriptionText = "完了済"
            )
        )
        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/pending.wav",
                status = ChunkStatus.PENDING
            )
        )
        val failedFile = File(tempDir, "failed.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 2,
                filePath = failedFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        val allChunks = chunkRepo.getBySession(sessionId)
        assertEquals(3, allChunks.size)

        assertEquals(ChunkStatus.DONE, allChunks[0].status)
        assertEquals("完了済", allChunks[0].transcriptionText)

        assertEquals(ChunkStatus.PENDING, allChunks[1].status)

        assertEquals(ChunkStatus.DONE, allChunks[2].status)
        assertEquals("テキスト", allChunks[2].transcriptionText)
    }

    @Test
    fun `different sessions - processed independently`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)

        val fileA = File(tempDir, "a_chunk_0.wav").also { it.writeBytes(ByteArray(100)) }
        chunkRepo.insert(
            ChunkEntity(
                sessionId = "session-A",
                chunkIndex = 0,
                filePath = fileA.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        chunkRepo.insert(
            ChunkEntity(
                sessionId = "session-B",
                chunkIndex = 0,
                filePath = "/tmp/missing_b.wav",
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        val sessionA = chunkRepo.getBySession("session-A")
        assertEquals(1, sessionA.size)
        assertEquals(ChunkStatus.DONE, sessionA[0].status)

        val sessionB = chunkRepo.getBySession("session-B")
        assertEquals(0, sessionB.size)
    }
}
