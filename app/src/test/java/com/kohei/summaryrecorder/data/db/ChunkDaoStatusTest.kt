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
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkDaoStatusTest {

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
    fun `PENDING to UPLOADING transition`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.PENDING
        ))

        dao.updateStatus(id, ChunkStatus.UPLOADING)

        val loaded = dao.getById(id)!!
        assertEquals(ChunkStatus.UPLOADING, loaded.status)
        assertNull(loaded.transcriptionText)
    }

    @Test
    fun `UPLOADING to DONE with transcription text`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.UPLOADING
        ))

        dao.updateStatus(id, ChunkStatus.DONE, "文字起こし結果テキスト")

        val loaded = dao.getById(id)!!
        assertEquals(ChunkStatus.DONE, loaded.status)
        assertEquals("文字起こし結果テキスト", loaded.transcriptionText)
    }

    @Test
    fun `UPLOADING to FAILED on error`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.UPLOADING
        ))

        dao.updateStatus(id, ChunkStatus.FAILED)

        val loaded = dao.getById(id)!!
        assertEquals(ChunkStatus.FAILED, loaded.status)
        assertNull(loaded.transcriptionText)
    }

    @Test
    fun `FAILED to UPLOADING retry`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.FAILED
        ))

        dao.updateStatus(id, ChunkStatus.UPLOADING)
        assertEquals(ChunkStatus.UPLOADING, dao.getById(id)!!.status)
    }

    @Test
    fun `get by status filters correctly`() = runTest {
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a", status = ChunkStatus.DONE))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/b", status = ChunkStatus.FAILED))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 2, filePath = "/c", status = ChunkStatus.DONE))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 3, filePath = "/d", status = ChunkStatus.PENDING))

        assertEquals(2, dao.getByStatus(ChunkStatus.DONE).size)
        assertEquals(1, dao.getByStatus(ChunkStatus.FAILED).size)
        assertEquals(1, dao.getByStatus(ChunkStatus.PENDING).size)
    }

    @Test
    fun `count by status`() = runTest {
        val sessionId = "s1"
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 0, filePath = "/a", status = ChunkStatus.DONE))
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 1, filePath = "/b", status = ChunkStatus.DONE))
        dao.insert(ChunkEntity(sessionId = sessionId, chunkIndex = 2, filePath = "/c", status = ChunkStatus.PENDING))

        assertEquals(2, dao.countByStatus(sessionId, ChunkStatus.DONE))
        assertEquals(1, dao.countByStatus(sessionId, ChunkStatus.PENDING))
        assertEquals(0, dao.countByStatus(sessionId, ChunkStatus.FAILED))
    }
}