package com.kohei.summaryrecorder.data.repository

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
import kotlin.test.assertTrue

/**
 * Bug fix verify: deleteById() 実装の正当性
 *
 * TranscriptionUploaderのretryFailedChunks()修正に伴い、
 * ChunkRepository/ChunkDaoにdeleteById()を追加。
 * 当該チャンクのみ削除、他チャンクは影響なし。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkRepositoryDeleteByIdTest {

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

    @Test
    fun `deleteById removes only the specified chunk`() = runTest {
        val sessionId = "delete-by-id-session"

        val id0 = chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.PENDING))
        val id1 = chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/b.wav", status = ChunkStatus.PENDING))
        val id2 = chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 2, filePath = "/c.wav", status = ChunkStatus.PENDING))

        // chunkIndex=1 (id1) を削除
        chunkRepo.deleteById(id1)

        val remaining = chunkRepo.getBySession(sessionId)
        assertEquals(2, remaining.size)
        assertEquals(listOf(0, 2), remaining.map { it.chunkIndex })
    }

    @Test
    fun `deleteById does not affect other sessions`() = runTest {
        val idA = chunkRepo.insert(ChunkEntity(sessionId = "session-A", chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.DONE))
        val idB = chunkRepo.insert(ChunkEntity(sessionId = "session-B", chunkIndex = 0, filePath = "/b.wav", status = ChunkStatus.DONE))

        // session-Aのチャンクを削除
        chunkRepo.deleteById(idA)

        assertEquals(0, chunkRepo.getBySession("session-A").size)
        assertEquals(1, chunkRepo.getBySession("session-B").size)
    }

    @Test
    fun `deleteById on non-existent id is no-op`() = runTest {
        val sessionId = "noop-session"
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/x.wav", status = ChunkStatus.PENDING))

        // 存在しないIDで削除 → クラッシュなし
        chunkRepo.deleteById(99999)

        assertEquals(1, chunkRepo.getBySession(sessionId).size)
    }

    @Test
    fun `deleteById then getByStatus returns correct remaining`() = runTest {
        val sessionId = "status-session"
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/a.wav", status = ChunkStatus.FAILED))
        val id1 = chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/b.wav", status = ChunkStatus.FAILED))
        chunkRepo.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 2, filePath = "/c.wav", status = ChunkStatus.FAILED))

        chunkRepo.deleteById(id1)

        val failed = chunkRepo.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failed.size)
        assertTrue(failed.none { it.id == id1 })
    }
}
