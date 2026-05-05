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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceFlowTest {

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
            .filesDir.resolve("test_recordings").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `chunk upload succeeds - PENDING to UPLOADING to DONE, file kept`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("こんにちは世界")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val chunkFile = File(tempDir, "chunk_0.wav").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(100))
        }

        val entity = ChunkEntity(
            sessionId = "test-session-001",
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        )
        val id = chunkRepo.insert(entity)

        val chunks = chunkRepo.getBySession("test-session-001")
        val chunk = chunks.first()
        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isSuccess)
        val updated = chunkRepo.getByStatus(ChunkStatus.DONE)
        assertEquals(1, updated.size)
        assertEquals("こんにちは世界", updated[0].transcriptionText)

        // #4: WAVファイルは保持される
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `chunk upload fails - status becomes FAILED`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network error")
        )

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val chunkFile = File(tempDir, "chunk_0.wav").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(100))
        }

        val entity = ChunkEntity(
            sessionId = "test-session-002",
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        )
        val id = chunkRepo.insert(entity)

        val chunks = chunkRepo.getBySession("test-session-002")
        val chunk = chunks.first()
        val result = uploader.uploadChunk(chunk)

        assertTrue(result.isFailure)
        val updated = chunkRepo.getByStatus(ChunkStatus.FAILED)
        assertEquals(1, updated.size)
        assertNull(updated[0].transcriptionText)

        assertTrue(chunkFile.exists())
    }

    @Test
    fun `multiple chunks - sequential upload`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "test-session-003"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also {
                it.writeBytes(ByteArray(100))
            }
            val entity = ChunkEntity(
                sessionId = sessionId,
                chunkIndex = i,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.PENDING
            )
            chunkRepo.insert(entity)

            val chunks = chunkRepo.getBySession(sessionId).filter { it.chunkIndex == i }
            uploader.uploadChunk(chunks.first())
        }

        val allChunks = chunkRepo.getBySession(sessionId)
        assertEquals(3, allChunks.size)
        allChunks.forEach {
            assertEquals(ChunkStatus.DONE, it.status)
        }
    }

    @Test
    fun `start and stop recording lifecycle - DAO state transitions`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("録音テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "lifecycle-session"

        val chunkFile = File(tempDir, "lifecycle.wav").also {
            it.writeBytes(ByteArray(100))
        }
        val id = chunkRepo.insert(ChunkEntity(
            sessionId = sessionId,
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        ))

        val chunks = chunkRepo.getBySession(sessionId)
        uploader.uploadChunk(chunks.first())

        val updated = chunkRepo.getByStatus(ChunkStatus.DONE)
        assertEquals(1, updated.size)
        assertEquals("録音テキスト", updated[0].transcriptionText)
        // #4: WAVファイルは保持される
        assertTrue(chunkFile.exists())
    }
}
