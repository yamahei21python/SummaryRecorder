package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * RecordingService起動時の初期化テスト。
 *
 * 検証項目:
 * - onCreate() で resetStuckUploads() が呼ばれ、
 *   残留UPLOADINGレコードがFAILEDにリセットされること
 *
 * ※ @AndroidEntryPoint環境でRobolectric.buildService不可のため、
 *    DAOのresetStuckUploads()を直接呼び出して検証。
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
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun `resetStuckUploads resets UPLOADING records to FAILED`() = runTest {
        // Arrange: UPLOADING状態のレコードを事前挿入（クラッシュ残留を模擬）
        val dao = db.chunkDao()
        dao.insert(ChunkEntity(
            sessionId = "old-session",
            chunkIndex = 0,
            filePath = "/tmp/old_chunk.wav",
            status = ChunkStatus.UPLOADING
        ))
        dao.insert(ChunkEntity(
            sessionId = "old-session",
            chunkIndex = 1,
            filePath = "/tmp/old_chunk_1.wav",
            status = ChunkStatus.UPLOADING
        ))

        // Act: onCreateで呼ばれるresetStuckUploadsを直接実行
        dao.resetStuckUploads()

        // Assert: 全UPLOADINGがFAILEDにリセットされている
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failedChunks.size)
        failedChunks.forEach {
            assertEquals(ChunkStatus.FAILED, it.status)
        }

        // UPLOADINGは0件
        val uploading = dao.getByStatus(ChunkStatus.UPLOADING)
        assertEquals(0, uploading.size)
    }

    @Test
    fun `resetStuckUploads does not affect DONE records`() = runTest {
        val dao = db.chunkDao()
        dao.insert(ChunkEntity(
            sessionId = "done-session",
            chunkIndex = 0,
            filePath = "/tmp/done_chunk.wav",
            status = ChunkStatus.DONE,
            transcriptionText = "already done"
        ))

        dao.resetStuckUploads()

        // Assert: DONEはそのまま
        val doneChunks = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, doneChunks.size)
        assertEquals("already done", doneChunks[0].transcriptionText)
    }

    @Test
    fun `resetStuckUploads does not affect PENDING records`() = runTest {
        val dao = db.chunkDao()
        dao.insert(ChunkEntity(
            sessionId = "pending-session",
            chunkIndex = 0,
            filePath = "/tmp/pending_chunk.wav",
            status = ChunkStatus.PENDING
        ))

        dao.resetStuckUploads()

        // Assert: PENDINGはそのまま
        val pendingChunks = dao.getByStatus(ChunkStatus.PENDING)
        assertEquals(1, pendingChunks.size)
    }
}
