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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceSelfHealTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        mockProvider = mockk<TranscriptionProvider>()
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("復旧テキスト")
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun `crash recovery - UPLOADING chunks reset to FAILED on restart`() = runTest {
        val dao = db.chunkDao()

        dao.insert(ChunkEntity(
            sessionId = "crash-session",
            chunkIndex = 0,
            filePath = "/tmp/crash_chunk_0.wav",
            status = ChunkStatus.UPLOADING
        ))
        dao.insert(ChunkEntity(
            sessionId = "crash-session",
            chunkIndex = 1,
            filePath = "/tmp/crash_chunk_1.wav",
            status = ChunkStatus.UPLOADING
        ))
        dao.insert(ChunkEntity(
            sessionId = "crash-session",
            chunkIndex = 2,
            filePath = "/tmp/crash_chunk_2.wav",
            status = ChunkStatus.DONE,
            transcriptionText = "完了済み"
        ))

        dao.resetStuckUploads()

        val failed = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failed.size)
        failed.forEach {
            assertEquals(ChunkStatus.FAILED, it.status)
        }

        val done = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, done.size)
        assertEquals("完了済み", done[0].transcriptionText)

        assertEquals(0, dao.getByStatus(ChunkStatus.UPLOADING).size)
    }

    @Test
    fun `retry after recovery - FAILED chunks can be retried`() = runTest {
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("retry_test").also { it.mkdirs() }

        try {
            val chunkFile = File(tempDir, "retry_chunk.wav").also {
                it.writeBytes(ByteArray(100))
            }
            val id = chunkRepo.insert(ChunkEntity(
                sessionId = "retry-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            ))

            uploader.retryFailedChunks()

            val updated = chunkRepo.getBySession("retry-session").first()
            assertEquals(ChunkStatus.DONE, updated.status)
            assertEquals("復旧テキスト", updated.transcriptionText)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `file missing - session records should be cleaned up`() = runTest {
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)

        chunkRepo.insert(ChunkEntity(
            sessionId = "missing-session",
            chunkIndex = 0,
            filePath = "/tmp/nonexistent_0.wav",
            status = ChunkStatus.FAILED
        ))
        chunkRepo.insert(ChunkEntity(
            sessionId = "missing-session",
            chunkIndex = 1,
            filePath = "/tmp/nonexistent_1.wav",
            status = ChunkStatus.FAILED
        ))

        val processedCount = uploader.retryFailedChunks()

        assertEquals(0, processedCount)
        val remaining = chunkRepo.getBySession("missing-session")
        assertEquals(0, remaining.size)
    }

    @Test
    fun `mixed session recovery - only crashed session affected`() = runTest {
        val dao = db.chunkDao()

        dao.insert(ChunkEntity(
            sessionId = "crashed-session",
            chunkIndex = 0,
            filePath = "/tmp/crash.wav",
            status = ChunkStatus.UPLOADING
        ))
        dao.insert(ChunkEntity(
            sessionId = "healthy-session",
            chunkIndex = 0,
            filePath = "/tmp/healthy.wav",
            status = ChunkStatus.DONE,
            transcriptionText = "正常完了"
        ))

        dao.resetStuckUploads()

        val crashed = dao.getBySession("crashed-session")
        assertEquals(1, crashed.size)
        assertEquals(ChunkStatus.FAILED, crashed[0].status)

        val healthy = dao.getBySession("healthy-session")
        assertEquals(1, healthy.size)
        assertEquals(ChunkStatus.DONE, healthy[0].status)
        assertEquals("正常完了", healthy[0].transcriptionText)
    }
}
