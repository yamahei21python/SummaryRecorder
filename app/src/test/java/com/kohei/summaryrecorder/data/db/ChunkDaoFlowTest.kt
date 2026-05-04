package com.kohei.summaryrecorder.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
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
class ChunkDaoFlowTest {

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
    fun `observeBySession emits initial empty list`() = runTest {
        dao.observeBySession("s1").test {
            // 空リストが最初にemitされる
            val initial = awaitItem()
            assertTrue(initial.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeBySession emits on insert`() = runTest {
        dao.observeBySession("s1").test {
            // 初期空リスト
            awaitItem()

            // insert
            dao.insert(ChunkEntity(
                sessionId = "s1", chunkIndex = 0,
                filePath = "/a", status = ChunkStatus.PENDING
            ))

            // 更新後のリスト
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals(0, updated[0].chunkIndex)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeBySession emits on status update`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.PENDING
        ))

        dao.observeBySession("s1").test {
            // 初期emit（既存データ1件）
            awaitItem()

            // ステータス更新
            dao.updateStatus(id, ChunkStatus.UPLOADING)

            val updated = awaitItem()
            assertEquals(ChunkStatus.UPLOADING, updated[0].status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeBySession does not emit for different session`() = runTest {
        dao.observeBySession("s1").test {
            awaitItem() // 初期空

            // 別セッションへのinsert
            dao.insert(ChunkEntity(
                sessionId = "s2", chunkIndex = 0,
                filePath = "/a", status = ChunkStatus.PENDING
            ))

            // s1には影響なし → 追加emitなしを確認
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}