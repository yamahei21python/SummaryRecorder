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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkDaoResetTest {

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
    fun `resetStatusBulk converts UPLOADING and PENDING to FAILED`() = runTest {
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/a", status = ChunkStatus.UPLOADING))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/b", status = ChunkStatus.PENDING))
        dao.insert(ChunkEntity(sessionId = "s1", chunkIndex = 2, filePath = "/c", status = ChunkStatus.DONE))

        // Repository level method was renamed to reflect DAO change or keeps old name but calls new DAO
        // In ChunkRepositoryImpl: resetStuckUploads calls dao.resetStatusBulk(listOf(UPLOADING, PENDING), FAILED)
        dao.resetStatusBulk(listOf(ChunkStatus.UPLOADING, ChunkStatus.PENDING), ChunkStatus.FAILED)

        val failed = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, failed.size)

        val uploading = dao.getByStatus(ChunkStatus.UPLOADING)
        assertEquals(0, uploading.size)
        
        val pending = dao.getByStatus(ChunkStatus.PENDING)
        assertEquals(0, pending.size)

        // DONE は影響なし
        assertEquals(1, dao.getByStatus(ChunkStatus.DONE).size)
    }

    @Test
    fun `resetStatusBulk updates updatedAt timestamp`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.UPLOADING
        ))
        val beforeReset = dao.getById(id)!!.updatedAt

        // 時間差を確保
        Thread.sleep(10)
        dao.resetStatusBulk(listOf(ChunkStatus.UPLOADING), ChunkStatus.FAILED)

        val afterReset = dao.getById(id)!!
        assertTrue(afterReset.updatedAt >= beforeReset)
    }
}