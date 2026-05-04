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

/**
 * RetryWorker: 複合状態テスト（既存テストの補完）。
 *
 * 検証項目:
 * - FAILED + PENDING混在 → PENDING無視、FAILEDのみ再送
 * - 全FAILEDチャンクのファイル消失 → セッション全削除、1回のみ実行
 * - 複数セッションで片方だけファイル消失
 * - UPLOADINGチャンクは処理対象外
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerMixedTest {

    private lateinit var db: AppDatabase
    private lateinit var mockRepo: TranscriptionRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockRepo = mockk<TranscriptionRepository>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_mixed").also { it.mkdirs() }

        ServiceLocator.overrideForTest(db, mockRepo)
    }

    @After
    fun tearDown() {
        ServiceLocator.clearTestOverrides()
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `FAILED and PENDING mixed - only FAILED retried`() = runTest {
        coEvery { mockRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val sessionId = "mixed-fp-session"

        // FAILED: 再送対象
        val failedFile = File(tempDir, "failed.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = failedFile.absolutePath, status = ChunkStatus.FAILED
        ))

        // PENDING: 無視
        val pendingFile = File(tempDir, "pending.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = pendingFile.absolutePath, status = ChunkStatus.PENDING
        ))

        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>(relaxed = true)
        )
        worker.doWork()

        val all = dao.getBySession(sessionId)
        assertEquals(2, all.size)

        // FAILED→DONE
        assertEquals(ChunkStatus.DONE, all[0].status)
        assertEquals("テキスト", all[0].transcriptionText)

        // PENDINGは不変
        assertEquals(ChunkStatus.PENDING, all[1].status)
    }

    @Test
    fun `all FAILED files missing - entire session deleted`() = runTest {
        val dao = db.chunkDao()
        val sessionId = "all-missing-session"

        // 全ファイル存在しない
        dao.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/tmp/missing_0.wav", status = ChunkStatus.FAILED
        ))
        dao.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = "/tmp/missing_1.wav", status = ChunkStatus.FAILED
        ))
        dao.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 2,
            filePath = "/tmp/missing_2.wav", status = ChunkStatus.FAILED
        ))

        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>(relaxed = true)
        )
        worker.doWork()

        // セッション全削除
        assertEquals(0, dao.getBySession(sessionId).size)
    }

    @Test
    fun `UPLOADING chunks are not processed`() = runTest {
        coEvery { mockRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val uploadFile = File(tempDir, "uploading.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(ChunkEntity(
            sessionId = "upload-session", chunkIndex = 0,
            filePath = uploadFile.absolutePath, status = ChunkStatus.UPLOADING
        ))

        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>(relaxed = true)
        )
        worker.doWork()

        // UPLOADINGはgetByStatus(FAILED)に含まれない → 不変
        val chunks = dao.getBySession("upload-session")
        assertEquals(1, chunks.size)
        assertEquals(ChunkStatus.UPLOADING, chunks[0].status)
    }

    @Test
    fun `multiple sessions - only one has missing files`() = runTest {
        coEvery { mockRepo.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()

        // Session A: ファイル存在 → 再送成功
        val fileA = File(tempDir, "a.wav").also { it.writeBytes(ByteArray(100)) }
        dao.insert(ChunkEntity(
            sessionId = "session-A", chunkIndex = 0,
            filePath = fileA.absolutePath, status = ChunkStatus.FAILED
        ))

        // Session B: ファイル消失 → セッション全削除
        dao.insert(ChunkEntity(
            sessionId = "session-B", chunkIndex = 0,
            filePath = "/tmp/missing_b.wav", status = ChunkStatus.FAILED
        ))

        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>(relaxed = true)
        )
        worker.doWork()

        // Session A: DONE
        val sessionA = dao.getBySession("session-A")
        assertEquals(1, sessionA.size)
        assertEquals(ChunkStatus.DONE, sessionA[0].status)

        // Session B: 全削除
        assertEquals(0, dao.getBySession("session-B").size)
    }

    @Test
    fun `FAILED chunk with large file - retried successfully`() = runTest {
        coEvery { mockRepo.transcribe(any<File>()) } returns Result.success("大きなファイルテキスト")

        val dao = db.chunkDao()
        val largeFile = File(tempDir, "large.wav").also {
            it.writeBytes(ByteArray(1024 * 100)) // 100KB
        }
        dao.insert(ChunkEntity(
            sessionId = "large-session", chunkIndex = 0,
            filePath = largeFile.absolutePath, status = ChunkStatus.FAILED
        ))

        val worker = RetryWorker(
            ApplicationProvider.getApplicationContext(),
            mockk<WorkerParameters>(relaxed = true)
        )
        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        val done = dao.getByStatus(ChunkStatus.DONE)
        assertEquals(1, done.size)
        assertEquals("大きなファイルテキスト", done[0].transcriptionText)
    }
}
