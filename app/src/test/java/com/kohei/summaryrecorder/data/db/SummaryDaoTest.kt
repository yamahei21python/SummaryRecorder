package com.kohei.summaryrecorder.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class SummaryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SummaryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.summaryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun testEntity(
        sessionId: String = "s1",
        status: SummaryStatus = SummaryStatus.RECORDED,
        title: String = "",
        isRead: Boolean = false
    ) = SummaryEntity(
        sessionId = sessionId,
        createdAt = 1000L,
        title = title,
        status = status,
        isRead = isRead,
        audioFilePath = "/recordings/$sessionId.wav",
        durationMs = 5000L
    )

    // ===== INSERT + READ =====

    @Test
    fun `insert and getBySessionId`() = runTest {
        val entity = testEntity("s1")
        dao.insert(entity)

        val result = dao.getBySessionId("s1")
        assertNotNull(result)
        assertEquals("s1", result.sessionId)
        assertEquals(SummaryStatus.RECORDED, result.status)
    }

    @Test
    fun `getAll returns createdAt DESC order`() = runTest {
        dao.insert(testEntity("s1").copy(createdAt = 1000L))
        dao.insert(testEntity("s2").copy(createdAt = 3000L))
        dao.insert(testEntity("s3").copy(createdAt = 2000L))

        val all = dao.getAll()
        assertEquals(3, all.size)
        assertEquals("s2", all[0].sessionId) // 新しい順
        assertEquals("s3", all[1].sessionId)
        assertEquals("s1", all[2].sessionId)
    }

    @Test
    fun `observeAll emits updates`() = runTest {
        dao.insert(testEntity("s1"))
        val first = dao.observeAll().first()
        assertEquals(1, first.size)

        dao.insert(testEntity("s2"))
        val second = dao.observeAll().first()
        assertEquals(2, second.size)
    }

    // ===== GET BY STATUS =====

    @Test
    fun `getByStatus filters correctly`() = runTest {
        dao.insert(testEntity("s1", SummaryStatus.RECORDED))
        dao.insert(testEntity("s2", SummaryStatus.DONE))
        dao.insert(testEntity("s3", SummaryStatus.ERROR))
        dao.insert(testEntity("s4", SummaryStatus.SUMMARIZING))

        val result = dao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING))
        assertEquals(2, result.size)
        assertTrue(result.all { it.status == SummaryStatus.RECORDED || it.status == SummaryStatus.SUMMARIZING })
    }

    // ===== UPDATE STATUS =====

    @Test
    fun `updateStatus changes status and errorMessage`() = runTest {
        dao.insert(testEntity("s1"))
        dao.updateStatus("s1", SummaryStatus.ERROR, "API failed")

        val result = dao.getBySessionId("s1")!!
        assertEquals(SummaryStatus.ERROR, result.status)
        assertEquals("API failed", result.errorMessage)
    }

    @Test
    fun `updateStatusAndContent sets all fields`() = runTest {
        dao.insert(testEntity("s1"))
        dao.updateStatusAndContent(
            "s1", SummaryStatus.DONE,
            "テストタイトル", "要約文", "転写文"
        )

        val result = dao.getBySessionId("s1")!!
        assertEquals(SummaryStatus.DONE, result.status)
        assertEquals("テストタイトル", result.title)
        assertEquals("要約文", result.summaryText)
        assertEquals("転写文", result.transcriptionText)
    }

    @Test
    fun `status transition RECORDED to SUMMARIZING to DONE`() = runTest {
        dao.insert(testEntity("s1", SummaryStatus.RECORDED))
        assertEquals(SummaryStatus.RECORDED, dao.getBySessionId("s1")!!.status)

        dao.updateStatus("s1", SummaryStatus.SUMMARIZING)
        assertEquals(SummaryStatus.SUMMARIZING, dao.getBySessionId("s1")!!.status)

        dao.updateStatusAndContent("s1", SummaryStatus.DONE, "タイトル", "要約", "転写")
        assertEquals(SummaryStatus.DONE, dao.getBySessionId("s1")!!.status)
    }

    // ===== UPDATE TITLE =====

    @Test
    fun `updateTitle changes title`() = runTest {
        dao.insert(testEntity("s1", title = "旧タイトル"))
        dao.updateTitle("s1", "新タイトル")

        assertEquals("新タイトル", dao.getBySessionId("s1")!!.title)
    }

    // ===== UPDATE READ =====

    @Test
    fun `updateRead changes isRead`() = runTest {
        dao.insert(testEntity("s1", isRead = false))
        dao.updateRead("s1", true)

        assertTrue(dao.getBySessionId("s1")!!.isRead)
    }

    @Test
    fun `countUnreadDone counts only DONE and unread`() = runTest {
        dao.insert(testEntity("s1", SummaryStatus.DONE, isRead = false))
        dao.insert(testEntity("s2", SummaryStatus.DONE, isRead = false))
        dao.insert(testEntity("s3", SummaryStatus.DONE, isRead = true))
        dao.insert(testEntity("s4", SummaryStatus.RECORDED, isRead = false))

        assertEquals(2, dao.countUnreadDone())
    }

    // ===== DELETE =====

    @Test
    fun `delete removes entity`() = runTest {
        dao.insert(testEntity("s1"))
        dao.delete("s1")

        assertNull(dao.getBySessionId("s1"))
    }

    @Test
    fun `delete non-existent session is no-op`() = runTest {
        dao.insert(testEntity("s1"))
        dao.delete("nonexistent") // クラッシュしない

        assertEquals(1, dao.getAll().size)
    }
}
