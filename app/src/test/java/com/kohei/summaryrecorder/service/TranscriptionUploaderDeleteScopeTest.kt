package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.service.TranscriptionUploader
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
 * Bug fix verify: deleteBySession() → deleteById() スコープ修正
 *
 * retryFailedChunks()内でファイル欠損時にdeleteBySession()を呼ぶと
 * セッション内の全チャンクが削除されるバグ。
 * deleteById()で当該チャンクのみ削除するように修正済。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class TranscriptionUploaderDeleteScopeTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockProvider = mockk<TranscriptionProvider>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_delete_scope").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `missing file deletes only that chunk, not entire session`() = runTest {
        // chunk0: ファイル存在 → 再送成功 → DONE
        // chunk1: ファイル欠損 → deleteByIdのみ
        // chunk2: ファイル存在 → 再送成功 → DONE
        val file0 = File(tempDir, "chunk_0.wav").also { it.writeBytes(ByteArray(100)) }
        val file2 = File(tempDir, "chunk_2.wav").also { it.writeBytes(ByteArray(100)) }

        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "delete-scope-session"

        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = file0.absolutePath, status = ChunkStatus.FAILED))
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/nonexistent/file.wav", status = ChunkStatus.FAILED))
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 2, filePath = file2.absolutePath, status = ChunkStatus.FAILED))

        uploader.retryFailedChunks()

        val remaining = chunkRepo.getBySession(sessionId)

        // 旧バグ: deleteBySession → 0件
        // 修正後: chunk1のみ削除 → 2件残存
        assertEquals(2, remaining.size, "欠損チャンクのみ削除、セッション全体は維持")
        assertEquals(listOf(0, 2), remaining.map { it.chunkIndex }.sorted())
        assertTrue(remaining.all { it.status == ChunkStatus.DONE })
    }

    @Test
    fun `all files missing deletes all chunks individually`() = runTest {
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "all-missing-session"

        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/missing0.wav", status = ChunkStatus.FAILED))
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/missing1.wav", status = ChunkStatus.FAILED))

        uploader.retryFailedChunks()

        val remaining = chunkRepo.getBySession(sessionId)
        assertEquals(0, remaining.size, "全ファイル欠損 → 全削除")
    }

    @Test
    fun `no missing files preserves all chunks`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("OK")
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "no-missing-session"

        for (i in 0..2) {
            val f = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = i, filePath = f.absolutePath, status = ChunkStatus.FAILED))
        }

        uploader.retryFailedChunks()

        val remaining = chunkRepo.getBySession(sessionId)
        assertEquals(3, remaining.size, "ファイル全存在 → 削除なし")
        assertTrue(remaining.all { it.status == ChunkStatus.DONE })
    }
}
