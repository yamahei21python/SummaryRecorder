package com.kohei.summaryrecorder.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SessionHistory
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * deleteSessionData / getSessionHistory / getFilePathsBySession の統合テスト
 * 実際のRoom DB + ファイルシステムで検証
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkRepositoryDeleteSessionTest {

    private lateinit var db: AppDatabase
    private lateinit var chunkRepo: ChunkRepositoryImpl

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        chunkRepo = ChunkRepositoryImpl(db.chunkDao())
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    // ===== getSessionHistory =====

    @Test
    fun `getSessionHistory returns grouped sessions`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/b.wav", status = ChunkStatus.FAILED))
        chunkRepo.insert(ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/c.wav", status = ChunkStatus.DONE))

        val history = chunkRepo.getSessionHistory()

        assertEquals(2, history.size)
        // ORDER BY MIN(created_at) DESC — s2が最新
        val s2Entry = history.first { it.sessionId == "s2" }
        assertEquals(1, s2Entry.totalChunks)
        assertEquals(1, s2Entry.doneChunks)
        assertEquals(0, s2Entry.failedChunks)

        val s1Entry = history.first { it.sessionId == "s1" }
        assertEquals(2, s1Entry.totalChunks)
        assertEquals(1, s1Entry.doneChunks)
        assertEquals(1, s1Entry.failedChunks)
    }

    @Test
    fun `getSessionHistory returns empty for no data`() = runTest {
        val history = chunkRepo.getSessionHistory()
        assertTrue(history.isEmpty())
    }

    // ===== getFilePathsBySession =====

    @Test
    fun `getFilePathsBySession returns all paths for session`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/b.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/c.wav", status = ChunkStatus.DONE))

        val paths = chunkRepo.getFilePathsBySession("s1")

        assertEquals(2, paths.size)
        assertTrue(paths.contains("/a.wav"))
        assertTrue(paths.contains("/b.wav"))
    }

    @Test
    fun `getFilePathsBySession returns empty for nonexistent session`() = runTest {
        val paths = chunkRepo.getFilePathsBySession("nonexistent")
        assertTrue(paths.isEmpty())
    }

    // ===== deleteSessionData =====

    @Test
    fun `deleteSessionData removes DB records for session`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/tmp/test_delete_a.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/tmp/test_delete_b.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/tmp/test_delete_c.wav", status = ChunkStatus.DONE))

        chunkRepo.deleteSessionData("s1")

        assertEquals(0, chunkRepo.getBySession("s1").size)
        assertEquals(1, chunkRepo.getBySession("s2").size)
    }

    @Test
    fun `deleteSessionData deletes existing files`() = runTest {
        val tempDir = System.getProperty("java.io.tmpdir")
        val file1 = File(tempDir, "test_sr_delete_1.wav")
        val file2 = File(tempDir, "test_sr_delete_2.wav")
        file1.writeBytes(byteArrayOf(0, 1, 2))
        file2.writeBytes(byteArrayOf(3, 4, 5))

        try {
            chunkRepo.insert(ChunkEntity(sessionId = "s-del", chunkIndex = 0, filePath = file1.absolutePath, status = ChunkStatus.DONE))
            chunkRepo.insert(ChunkEntity(sessionId = "s-del", chunkIndex = 1, filePath = file2.absolutePath, status = ChunkStatus.DONE))

            chunkRepo.deleteSessionData("s-del")

            assertFalse(file1.exists())
            assertFalse(file2.exists())
            assertEquals(0, chunkRepo.getBySession("s-del").size)
        } finally {
            file1.delete()
            file2.delete()
        }
    }

    @Test
    fun `deleteSessionData handles nonexistent files gracefully`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "s-ghost", chunkIndex = 0, filePath = "/nonexistent/path/ghost.wav", status = ChunkStatus.DONE))

        // クラッシュしないこと
        chunkRepo.deleteSessionData("s-ghost")

        assertEquals(0, chunkRepo.getBySession("s-ghost").size)
    }

    @Test
    fun `deleteSessionData on nonexistent session is no-op`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "existing", chunkIndex = 0, filePath = "/x.wav", status = ChunkStatus.DONE))

        chunkRepo.deleteSessionData("nonexistent")

        assertEquals(1, chunkRepo.getBySession("existing").size)
    }

    @Test
    fun `deleteSessionData then getSessionHistory reflects removal`() = runTest {
        chunkRepo.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.DONE))
        chunkRepo.insert(ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/b.wav", status = ChunkStatus.DONE))

        assertEquals(2, chunkRepo.getSessionHistory().size)

        chunkRepo.deleteSessionData("s1")

        val history = chunkRepo.getSessionHistory()
        assertEquals(1, history.size)
        assertEquals("s2", history[0].sessionId)
    }
}
