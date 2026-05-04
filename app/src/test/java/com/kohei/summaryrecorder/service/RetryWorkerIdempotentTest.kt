package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.di.ServiceLocator
import io.mockk.any
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

/**
 * RetryWorker: 冪等性テスト（Phase 4）。
 *
 * 検証項目:
 * - 2回連続doWork(): 2回目はFAILEDなしで安全終了
 * - ファイル消失: セッション全削除（ゾンビレコード防止）
 * - ファイル消失+正常チャンク混在: セッション単位で全削除
 * - DONE/PENDINGチャンクは処理対象外
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerIdempotentTest {

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
            .filesDir.resolve("test_retry_idempotent").also { it.mkdirs() }

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
    fun `idempotent - second doWork has no FAILED chunks`() = runTest {
        // Arrange: 全チャンク再送成功
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val sessionId = "idempotent-session"

        for (i in 0..1) {
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

        // Act: 1回目
        val worker1 = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker1.doWork()

        // Assert: 全DONE
        assertEquals(2, dao.getByStatus(ChunkStatus.DONE).size)
        assertEquals(0, dao.getByStatus(ChunkStatus.FAILED).size)

        // Act: 2回目（FAILEDなし）
        val worker2 = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        val result2 = worker2.doWork()

        // Assert: 変化なし（冪等）
        assertEquals(androidx.work.ListenableWorker.Result.success(), result2)
        assertEquals(2, dao.getByStatus(ChunkStatus.DONE).size)
        assertEquals(0, dao.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `file missing - deletes entire session`() = runTest {
        // Arrange: ファイルパスが存在しないFAILEDチャンク
        val dao = db.chunkDao()
        val sessionId = "missing-file-session"

        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = "/tmp/nonexistent_chunk_0.wav", // 存在しない
                status = ChunkStatus.FAILED
            )
        )
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/nonexistent_chunk_1.wav", // 存在しない
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker.doWork()

        // Assert: セッション全削除
        assertEquals(0, dao.getBySession(sessionId).size)
        assertEquals(0, dao.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `file missing in one chunk - deletes entire session including intact files`() = runTest {
        // Arrange: 1つのファイル消失 + 1つのファイル存在
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val sessionId = "partial-missing-session"

        // chunk_0: ファイル存在
        val existingFile = File(tempDir, "existing.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = existingFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )
        // chunk_1: ファイル消失
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/gone.wav",
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker.doWork()

        // Assert: セッション全削除（chunk_0もchunk_1も消える）
        // ※ グループ化順序はHashMap依存。chunk_1が先に来たらセッション全削除。
        //    chunk_0が先に来たらDONE→chunk_1でセッション削除。
        //    どちらにしても最終的にセッションは全削除されるべき
        val remaining = dao.getBySession(sessionId)
        assertEquals(0, remaining.size)
    }

    @Test
    fun `DONE and PENDING chunks are not processed`() = runTest {
        // Arrange: DONE / PENDING / FAILED混在
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val sessionId = "mixed-status-session"

        // DONE: 処理対象外
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 0,
                filePath = "/tmp/done.wav",
                status = ChunkStatus.DONE,
                transcriptionText = "完了済"
            )
        )
        // PENDING: 処理対象外
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 1,
                filePath = "/tmp/pending.wav",
                status = ChunkStatus.PENDING
            )
        )
        // FAILED: 再送対象
        val failedFile = File(tempDir, "failed.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(
            ChunkEntity(
                sessionId = sessionId,
                chunkIndex = 2,
                filePath = failedFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker.doWork()

        // Assert
        val allChunks = dao.getBySession(sessionId)
        assertEquals(3, allChunks.size)

        // DONE: 変化なし
        assertEquals(ChunkStatus.DONE, allChunks[0].status)
        assertEquals("完了済", allChunks[0].transcriptionText)

        // PENDING: 変化なし
        assertEquals(ChunkStatus.PENDING, allChunks[1].status)

        // FAILED→DONE: 再送成功
        assertEquals(ChunkStatus.DONE, allChunks[2].status)
        assertEquals("テキスト", allChunks[2].transcriptionText)
    }

    @Test
    fun `different sessions - processed independently`() = runTest {
        // Arrange: 2セッション、それぞれ別々に処理
        coEvery { mockTranscriptionRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()

        // Session A: 全ファイル存在
        val fileA = File(tempDir, "a_chunk_0.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(
            ChunkEntity(
                sessionId = "session-A",
                chunkIndex = 0,
                filePath = fileA.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Session B: ファイル消失
        dao.insert(
            ChunkEntity(
                sessionId = "session-B",
                chunkIndex = 0,
                filePath = "/tmp/missing_b.wav",
                status = ChunkStatus.FAILED
            )
        )

        // Act
        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>()
        )
        worker.doWork()

        // Assert: Session A → DONE
        val sessionA = dao.getBySession("session-A")
        assertEquals(1, sessionA.size)
        assertEquals(ChunkStatus.DONE, sessionA[0].status)

        // Assert: Session B → セッション全削除
        val sessionB = dao.getBySession("session-B")
        assertEquals(0, sessionB.size)
    }
}
