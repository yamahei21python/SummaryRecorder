package com.kohei.summaryrecorder.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChunkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chunkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and get by id`() = runTest {
        val entity = ChunkEntity(
            sessionId = "session-1",
            chunkIndex = 0,
            filePath = "/tmp/chunk_0.wav",
            status = ChunkStatus.PENDING
        )

        val id = dao.insert(entity)
        val loaded = dao.getById(id)

        assertNotNull(loaded)
        assertEquals("session-1", loaded!!.sessionId)
        assertEquals(0, loaded.chunkIndex)
        assertEquals("/tmp/chunk_0.wav", loaded.filePath)
        assertEquals(ChunkStatus.PENDING, loaded.status)
        assertNull(loaded.transcriptionText)
    }

    @Test
    fun `get by session returns ordered by chunkIndex`() = runTest {
        val sessionId = "session-1"
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 2, filePath = "/c2", status = ChunkStatus.PENDING))
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/c0", status = ChunkStatus.PENDING))
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/c1", status = ChunkStatus.PENDING))

        val result = dao.getBySession(sessionId)

        assertEquals(3, result.size)
        assertEquals(0, result[0].chunkIndex)
        assertEquals(1, result[1].chunkIndex)
        assertEquals(2, result[2].chunkIndex)
    }

    @Test
    fun `delete by session removes only target session`() = runTest {
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a", status = ChunkStatus.DONE))
        dao.insert(ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/b", status = ChunkStatus.DONE))

        dao.deleteBySession("s1")

        assertEquals(0, dao.getBySession("s1").size)
        assertEquals(1, dao.getBySession("s2").size)
    }

    @Test
    fun `delete by id removes single record`() = runTest {
        val id = dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a", status = ChunkStatus.PENDING))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/b", status = ChunkStatus.PENDING))

        dao.deleteById(id)

        assertNull(dao.getById(id))
        assertEquals(1, dao.getBySession("s1").size)
    }
}