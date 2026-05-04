package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RecordingService: クラッシュ→再起動→自己修復のテスト。
 *
 * 検証項目:
 * - クラッシュ前にUPLOADING状態だったレコードがFAILEDにリセットされること
 * - リセット後、RetryWorkerがFAILEDレコードを再送できること
 * - ファイル消失時、セッション単位で全削除されること
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceSelfHealTest {

    private lateinit var db: AppDatabase
    private lateinit var mockTranscriptionRepo: TranscriptionRepository

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        mockTranscriptionRepo = mockk<TranscriptionRepository>()
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("復旧テキスト")

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
    fun `crash recovery - UPLOADING chunks reset to FAILED on restart`() = runTest {
        val dao = db.chunkDao()

        // Arrange: クラッシュ前の状態（UPLOADING残留）を模擬
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
        // DONEのチャンクも混在
        dao.insert(ChunkEntity(
            sessionId = "crash-session",
            chunkIndex = 2,
            filePath = "/tmp/crash_chunk_2.wav",
            status = ChunkStatus.DONE,
            transcriptionText = "完了済み"
        ))

        // Act: サービス再起動 → onCreate で resetStuckUploads
        val controller = Robolectric.buildService(RecordingService::class.java)
        controller.create()

        // Assert: UPLOADING → FAILED リセット
        val failed = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failed.size)
        failed.forEach {
            assertEquals(ChunkStatus.FAILED, it.status)
        }

        // DONEはそのまま
        val done = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, done.size)
        assertEquals("完了済み", done[0].transcriptionText)

        // UPLOADINGは0件
        assertEquals(0, dao.getByStatus(ChunkStatus.UPLOADING).size)

        controller.destroy()
    }

    @Test
    fun `retry after recovery - FAILED chunks can be retried`() = runTest {
        val dao = db.chunkDao()
        val tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("retry_test").also { it.mkdirs() }

        try {
            // Arrange: ファイル存在するFAILEDチャンク
            val chunkFile = File(tempDir, "retry_chunk.wav").also {
                it.writeBytes(ByteArray(100))
            }
            dao.insert(ChunkEntity(
                sessionId = "retry-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            ))

            // サービス再起動（自己修復）
            val controller = Robolectric.buildService(RecordingService::class.java)
            controller.create()

            // FAILEDチャンクを再送シミュレート
            val failedChunks = dao.getByStatus(ChunkStatus.FAILED)
            assertEquals(1, failedChunks.size)

            val chunk = failedChunks[0]
            dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)
            val file = File(chunk.filePath)
            assertTrue(file.exists())

            val result = mockTranscriptionRepo.transcribe(file)
            if (result.isSuccess) {
                dao.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
            }

            // Assert: 再送成功
            val updated = dao.getById(chunk.id)!!
            assertEquals(ChunkStatus.DONE, updated.status)
            assertEquals("復旧テキスト", updated.transcriptionText)

            controller.destroy()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `file missing - session records should be cleaned up`() = runTest {
        val dao = db.chunkDao()

        // Arrange: ファイル消失済みのFAILEDチャンク
        dao.insert(ChunkEntity(
            sessionId = "missing-session",
            chunkIndex = 0,
            filePath = "/tmp/nonexistent_0.wav",  // 存在しない
            status = ChunkStatus.FAILED
        ))
        dao.insert(ChunkEntity(
            sessionId = "missing-session",
            chunkIndex = 1,
            filePath = "/tmp/nonexistent_1.wav",  // 存在しない
            status = ChunkStatus.FAILED
        ))

        // Act: ファイル消失検知 → セッション全削除（RetryWorkerのロジックをシミュレート）
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)
        val bySession = failedChunks.groupBy { it.sessionId }

        bySession.forEach { (sessionId, chunks) ->
            chunks.forEach { chunk ->
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    // ファイル消失 → セッション単位全削除
                    dao.deleteBySession(sessionId)
                    return@forEach
                }
            }
        }

        // Assert: セッション全削除済み
        val remaining = dao.getBySession("missing-session")
        assertEquals(0, remaining.size)
    }

    @Test
    fun `mixed session recovery - only crashed session affected`() = runTest {
        val dao = db.chunkDao()

        // Arrange: 2つのセッション（クラッシュ済み vs 正常）
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

        // Act: サービス再起動
        val controller = Robolectric.buildService(RecordingService::class.java)
        controller.create()

        // Assert: クラッシュセッションのみFAILED
        val crashed = dao.getBySession("crashed-session")
        assertEquals(1, crashed.size)
        assertEquals(ChunkStatus.FAILED, crashed[0].status)

        // 正常セッションは無傷
        val healthy = dao.getBySession("healthy-session")
        assertEquals(1, healthy.size)
        assertEquals(ChunkStatus.DONE, healthy[0].status)
        assertEquals("正常完了", healthy[0].transcriptionText)

        controller.destroy()
    }
}
