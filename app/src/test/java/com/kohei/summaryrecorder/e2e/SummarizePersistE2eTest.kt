package com.kohei.summaryrecorder.e2e

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.data.model.SummarizeOutput
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
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

/**
 * E2E: SummarizeUseCase.executeAndPersist() をreal Room DBで検証。
 * DB内のステータス遷移 (RECORDED → SUMMARIZING → DONE/ERROR) と
 * データ永続化が正しく行われることを確認する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class SummarizePersistE2eTest {

    private lateinit var db: AppDatabase
    private lateinit var mockSummary: SummaryProvider
    private lateinit var chunkRepo: ChunkRepositoryImpl

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockSummary = mockk()
        chunkRepo = ChunkRepositoryImpl(db.chunkDao())
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun `executeAndPersist success - RECORDED to SUMMARIZING to DONE`() = runTest {
        val sessionId = "persist-ok"

        // チャンク準備
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/dummy.wav", status = ChunkStatus.DONE,
            transcriptionText = "会議の内容"
        ))

        // SummaryDaoにRECORDED状態でinsert
        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = "/merged.wav",
            durationMs = 5000L, status = SummaryStatus.RECORDED
        ))

        // summarize成功
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("タイトル", "要約テキスト")
        )

        val useCase = SummarizeUseCase(chunkRepo, mockSummary)
        useCase.executeAndPersist(sessionId, db.summaryDao())

        // DB検証
        val entity = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.DONE, entity.status)
        assertEquals("タイトル", entity.title)
        assertEquals("要約テキスト", entity.summaryText)
        assertTrue(entity.transcriptionText.contains("会議の内容"))
        assertNull(entity.errorMessage)
    }

    @Test
    fun `executeAndPersist failure - RECORDED to SUMMARIZING to ERROR`() = runTest {
        val sessionId = "persist-fail"

        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/dummy.wav", status = ChunkStatus.DONE,
            transcriptionText = "テキスト"
        ))

        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = "/merged.wav",
            durationMs = 5000L, status = SummaryStatus.RECORDED
        ))

        // summarize失敗
        coEvery { mockSummary.summarize(any()) } returns Result.failure(
            RuntimeException("API quota exceeded")
        )

        val useCase = SummarizeUseCase(chunkRepo, mockSummary)
        useCase.executeAndPersist(sessionId, db.summaryDao())

        val entity = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.ERROR, entity.status)
        assertEquals("API quota exceeded", entity.errorMessage)
        // 元のテキストは変更されない
        assertEquals("", entity.summaryText)
        assertEquals("", entity.title)
    }

    @Test
    fun `executeAndPersist with mixed DONE and FAILED chunks - warning in summary`() = runTest {
        val sessionId = "persist-mixed"

        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/d.wav", status = ChunkStatus.DONE,
            transcriptionText = "正常テキスト"
        ))
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = "/f.wav", status = ChunkStatus.FAILED
        ))

        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = "/m.wav",
            durationMs = 5000L, status = SummaryStatus.RECORDED
        ))

        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("会議", "要約")
        )

        val useCase = SummarizeUseCase(chunkRepo, mockSummary)
        useCase.executeAndPersist(sessionId, db.summaryDao())

        val entity = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.DONE, entity.status)
        assertTrue(entity.summaryText.contains("【注意】"))
        assertTrue(entity.summaryText.contains("要約"))
        assertTrue(entity.transcriptionText.contains("正常テキスト"))
        assertTrue(entity.transcriptionText.contains("[音声認識エラー]"))
    }

    @Test
    fun `executeAndPersist with empty chunks - no data error`() = runTest {
        val sessionId = "persist-empty"

        // チャンクなし
        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = "/m.wav",
            durationMs = 0L, status = SummaryStatus.RECORDED
        ))

        val useCase = SummarizeUseCase(chunkRepo, mockSummary)
        useCase.executeAndPersist(sessionId, db.summaryDao())

        val entity = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.ERROR, entity.status)
        assertEquals("録音データがありません", entity.errorMessage)
    }

    @Test
    fun `executeAndPersist idempotent on SUMMARIZING status`() = runTest {
        val sessionId = "persist-idempotent"

        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = "/d.wav", status = ChunkStatus.DONE,
            transcriptionText = "テキスト"
        ))

        // SUMMARIZING状態で開始 (クラッシュ再開シミュレーション)
        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = "/m.wav",
            durationMs = 5000L, status = SummaryStatus.SUMMARIZING
        ))

        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("t", "s")
        )

        val useCase = SummarizeUseCase(chunkRepo, mockSummary)
        useCase.executeAndPersist(sessionId, db.summaryDao())

        // SUMMARIZING → SUMMARIZING (再設定) → DONE
        val entity = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.DONE, entity.status)
    }
}
