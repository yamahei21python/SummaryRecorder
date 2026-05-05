package com.kohei.summaryrecorder.data.db

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
class ChunkDaoUpdateTextTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChunkDao

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.chunkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updateStatus with text overwrites existing transcriptionText`() = runTest {
        val chunk = ChunkEntity("s1", 0, "/path", ChunkStatus.PENDING)
        val id = dao.insert(chunk)

        dao.updateStatus(id, ChunkStatus.DONE, "New Text")
        
        val updated = dao.getBySession("s1").first()
        assertEquals("New Text", updated.transcriptionText)
        assertEquals(ChunkStatus.DONE, updated.status)
    }

    @Test
    fun `updateStatus with null text clears existing transcriptionText`() = runTest {
        val chunk = ChunkEntity("s1", 0, "/path", ChunkStatus.DONE, transcriptionText = "Old Text")
        val id = dao.insert(chunk)

        dao.updateStatus(id, ChunkStatus.FAILED, null)
        
        val updated = dao.getBySession("s1").first()
        assertNull(updated.transcriptionText)
        assertEquals(ChunkStatus.FAILED, updated.status)
    }
}
