package com.kohei.summaryrecorder.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.AppDatabase
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
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * RecordingService起動時の初期化テスト。
 *
 * 検証項目:
 * - onCreate() で resetStuckUploads() が呼ばれ、
 *   残留UPLOADINGレコードがFAILEDにリセットされること
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceInitTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        val mockTranscriptionRepo = mockk<TranscriptionRepository>()
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("dummy text")

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
    }

    @Test
    fun `onCreate resets stuck UPLOADING records to FAILED`() = runTest {
        // Arrange: UPLOADING状態のレコードを事前挿入（クラッシュ残留を模擬）
        val dao = db.chunkDao()
        dao.insert(
            com.kohei.summaryrecorder.data.db.ChunkEntity(
                sessionId = "old-session",
                chunkIndex = 0,
                filePath = "/tmp/old_chunk.wav",
                status = ChunkStatus.UPLOADING
            )
        )
        dao.insert(
            com.kohei.summaryrecorder.data.db.ChunkEntity(
                sessionId = "old-session",
                chunkIndex = 1,
                filePath = "/tmp/old_chunk_1.wav",
                status = ChunkStatus.UPLOADING
            )
        )

        // Act: Service起動（onCreate → resetStuckUploads）
        val serviceController = Robolectric.buildService(
            RecordingService::class.java
        )
        val service = serviceController.create().get()

        // Assert: 全UPLOADINGがFAILEDにリセットされている
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failedChunks.size)
        failedChunks.forEach {
            assertEquals(ChunkStatus.FAILED, it.status)
        }

        // UPLOADINGは0件
        val uploading = dao.getByStatus(ChunkStatus.UPLOADING)
        assertEquals(0, uploading.size)

        serviceController.destroy()
    }

    @Test
    fun `onCreate does not affect DONE records`() = runTest {
        // Arrange
        val dao = db.chunkDao()
        dao.insert(
            com.kohei.summaryrecorder.data.db.ChunkEntity(
                sessionId = "done-session",
                chunkIndex = 0,
                filePath = "/tmp/done_chunk.wav",
                status = ChunkStatus.DONE,
                transcriptionText = "already done"
            )
        )

        // Act
        val serviceController = Robolectric.buildService(
            RecordingService::class.java
        )
        serviceController.create()

        // Assert: DONEはそのまま
        val doneChunks = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, doneChunks.size)
        assertEquals("already done", doneChunks[0].transcriptionText)

        serviceController.destroy()
    }

    @Test
    fun `onCreate does not affect PENDING records`() = runTest {
        // Arrange
        val dao = db.chunkDao()
        dao.insert(
            com.kohei.summaryrecorder.data.db.ChunkEntity(
                sessionId = "pending-session",
                chunkIndex = 0,
                filePath = "/tmp/pending_chunk.wav",
                status = ChunkStatus.PENDING
            )
        )

        // Act
        val serviceController = Robolectric.buildService(
            RecordingService::class.java
        )
        serviceController.create()

        // Assert: PENDINGはそのまま
        val pendingChunks = dao.getByStatus(ChunkStatus.PENDING)
        assertEquals(1, pendingChunks.size)

        serviceController.destroy()
    }
}
