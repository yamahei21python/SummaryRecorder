package com.kohei.summaryrecorder.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * ChunkDao.casStatus(): 原子性・同時実行レース検証。
 *
 * SQLiteのrow-level lockingによりcasStatus()が原子性を保持するかを確認。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class ChunkDaoCasRaceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChunkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.createInMemory(context)
        dao = db.chunkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `casStatus only updates matching status`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.PENDING
        ))

        // PENDINGなのに UPLOADING→FAILED を試す → 0件
        val wrongSource = dao.casStatus(id, listOf(ChunkStatus.UPLOADING), ChunkStatus.FAILED)
        assertEquals(0, wrongSource)

        // PENDING→UPLOADING → 1件更新
        val correctSource = dao.casStatus(id, listOf(ChunkStatus.PENDING), ChunkStatus.UPLOADING)
        assertEquals(1, correctSource)

        // 確認: ステータスがUPLOADINGに変更されている
        assertEquals(ChunkStatus.UPLOADING, dao.getById(id)!!.status)
    }

    @Test
    fun `casStatus is atomic - concurrent calls on same chunk`() = runTest {
        val id = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.PENDING
        ))

        // 2つのコルーチンで同時にcasStatus(PENDING→UPLOADING)
        val deferred1 = async { dao.casStatus(id, listOf(ChunkStatus.PENDING), ChunkStatus.UPLOADING) }
        val deferred2 = async { dao.casStatus(id, listOf(ChunkStatus.PENDING), ChunkStatus.UPLOADING) }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // 片方のみ成功 → 合計1件
        assertEquals(1, result1 + result2, "同時casStatus: 片方のみ成功して合計1件更新")

        // 状態はUPLOADING
        assertEquals(ChunkStatus.UPLOADING, dao.getById(id)!!.status)
    }

    @Test
    fun `casStatus with multiple source statuses`() = runTest {
        // FAILEDチャンク
        val failedId = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 0,
            filePath = "/a", status = ChunkStatus.FAILED
        ))

        // FAILEDとPENDING両方をソースにしてUPLOADINGに → FAILEDチャンクが更新される
        val result = dao.casStatus(failedId, listOf(ChunkStatus.FAILED, ChunkStatus.PENDING), ChunkStatus.UPLOADING)
        assertEquals(1, result)
        assertEquals(ChunkStatus.UPLOADING, dao.getById(failedId)!!.status)

        // PENDINGチャンクでも同様に成功することを確認
        val pendingId = dao.insert(ChunkEntity(
            sessionId = "s1", chunkIndex = 1,
            filePath = "/b", status = ChunkStatus.PENDING
        ))

        val result2 = dao.casStatus(pendingId, listOf(ChunkStatus.FAILED, ChunkStatus.PENDING), ChunkStatus.UPLOADING)
        assertEquals(1, result2)
        assertEquals(ChunkStatus.UPLOADING, dao.getById(pendingId)!!.status)
    }
}
