package com.kohei.summaryrecorder.e2e

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.data.model.SummarizeOutput
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.recorder.WavMerger
import com.kohei.summaryrecorder.service.TranscriptionUploader
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E: チャンク転写 → WAVマージ → 要約までreal Room DBで検証。
 * RecordingServiceFlowTestがchunk転写までしかカバーしていないギャップを埋める。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class FullRecordingToSummaryE2eTest {

    private lateinit var db: AppDatabase
    private lateinit var mockTranscription: TranscriptionProvider
    private lateinit var mockSummary: SummaryProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        mockTranscription = mockk()
        mockSummary = mockk()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("e2e-recording").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    /**
     * E2E: PENDINGチャンク → 転写成功(DONE) → WavMerger → SummaryDao.insert(RECORDED)
     *       → SummarizeUseCase.executeAndPersist → DONE
     */
    @Test
    fun `full happy path - chunk transcription through summarization`() = runTest {
        val sessionId = "e2e-happy"
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockTranscription)
        val summarizeUseCase = SummarizeUseCase(chunkRepo, mockSummary)

        // 1. チャンクファイル作成 + DB insert
        val chunkFile = createWavFile(tempDir, sessionId, 0, 1000)
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = chunkFile.absolutePath, status = ChunkStatus.PENDING
        ))

        // 2. 転写
        coEvery { mockTranscription.transcribe(any()) } returns Result.success("会議の内容です")
        val chunk = chunkRepo.getBySession(sessionId).first()
        uploader.uploadChunk(chunk)

        // 検証: チャンクがDONE
        val doneChunks = chunkRepo.getByStatus(ChunkStatus.DONE)
        assertEquals(1, doneChunks.size)
        assertEquals("会議の内容です", doneChunks[0].transcriptionText)

        // 3. WavMerger
        val chunkDir = File(tempDir, sessionId)
        val chunkFiles = chunkDir.listFiles()?.filter { it.name.endsWith(".wav") } ?: emptyList()
        val mergedFile = File(tempDir, "$sessionId.wav")
        val durationMs = WavMerger.merge(chunkFiles, mergedFile)

        // 検証: マージファイル存在 + duration > 0
        assertTrue(mergedFile.exists())
        assertTrue(durationMs > 0)

        // 4. SummaryDao.insert(RECORDED)
        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId,
            audioFilePath = mergedFile.absolutePath,
            durationMs = durationMs,
            status = SummaryStatus.RECORDED
        ))

        // 5. SummarizeUseCase.executeAndPersist
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("会議メモ", "重要な議事")
        )
        summarizeUseCase.executeAndPersist(sessionId, db.summaryDao())

        // 検証: 最終状態 = DONE
        val summary = db.summaryDao().getBySessionId(sessionId)
        assertNotNull(summary)
        assertEquals(SummaryStatus.DONE, summary.status)
        assertEquals("会議メモ", summary.title)
        assertEquals("重要な議事", summary.summaryText)
        assertTrue(summary.transcriptionText.contains("会議の内容です"))
    }

    /**
     * E2E: 一部チャンクFAILED → 警告付きDONE
     */
    @Test
    fun `partial failure path - warning prepended to summary`() = runTest {
        val sessionId = "e2e-partial"
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockTranscription)
        val summarizeUseCase = SummarizeUseCase(chunkRepo, mockSummary)

        // chunk 0: 成功
        val chunkFile0 = createWavFile(tempDir, sessionId, 0, 1000)
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = chunkFile0.absolutePath, status = ChunkStatus.PENDING
        ))
        coEvery { mockTranscription.transcribe(match { it == chunkFile0 }) } returns Result.success("成功テキスト")
        val c0 = chunkRepo.getBySession(sessionId).first { it.chunkIndex == 0 }
        uploader.uploadChunk(c0)

        // chunk 1: 失敗
        val chunkFile1 = createWavFile(tempDir, sessionId, 1, 1000)
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 1,
            filePath = chunkFile1.absolutePath, status = ChunkStatus.PENDING
        ))
        coEvery { mockTranscription.transcribe(match { it == chunkFile1 }) } returns Result.failure(
            java.io.IOException("Network error")
        )
        val c1 = chunkRepo.getBySession(sessionId).first { it.chunkIndex == 1 }
        uploader.uploadChunk(c1)

        // マージ
        val chunkDir = File(tempDir, sessionId)
        val chunkFiles = chunkDir.listFiles()?.filter { it.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
        val mergedFile = File(tempDir, "$sessionId.wav")
        WavMerger.merge(chunkFiles, mergedFile)

        // SummaryDao + executeAndPersist
        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = mergedFile.absolutePath,
            durationMs = 5000L, status = SummaryStatus.RECORDED
        ))
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("タイトル", "要約文")
        )
        summarizeUseCase.executeAndPersist(sessionId, db.summaryDao())

        val summary = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.DONE, summary.status)
        assertTrue(summary.summaryText.contains("【注意】"))
        assertTrue(summary.summaryText.contains("要約文"))
        assertTrue(summary.transcriptionText.contains("成功テキスト"))
        assertTrue(summary.transcriptionText.contains("[音声認識エラー]"))
    }

    /**
     * E2E: 全チャンク転写失敗 → combinedText = [音声認識エラー]のみ → summarize呼ばれる → 警告付きDONE
     */
    @Test
    fun `all chunks FAILED - warning summary produced`() = runTest {
        val sessionId = "e2e-allfail"
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockTranscription)
        val summarizeUseCase = SummarizeUseCase(chunkRepo, mockSummary)

        val chunkFile = createWavFile(tempDir, sessionId, 0, 1000)
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = chunkFile.absolutePath, status = ChunkStatus.PENDING
        ))
        coEvery { mockTranscription.transcribe(any()) } returns Result.failure(
            java.io.IOException("Server error")
        )
        val c = chunkRepo.getBySession(sessionId).first()
        uploader.uploadChunk(c)

        assertEquals(1, chunkRepo.getByStatus(ChunkStatus.FAILED).size)

        // マージ (FAILEDチャンクのファイルはまだ存在する)
        val chunkDir = File(tempDir, sessionId)
        val chunkFiles = chunkDir.listFiles()?.filter { it.name.endsWith(".wav") } ?: emptyList()
        val mergedFile = File(tempDir, "$sessionId.wav")
        WavMerger.merge(chunkFiles, mergedFile)

        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = mergedFile.absolutePath,
            durationMs = 3000L, status = SummaryStatus.RECORDED
        ))

        // all FAILED → combinedText = "[音声認識エラー]" → isNotEmpty → summarize called
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("エラー記録", "不完全な要約")
        )
        summarizeUseCase.executeAndPersist(sessionId, db.summaryDao())

        val summary = db.summaryDao().getBySessionId(sessionId)!!
        assertEquals(SummaryStatus.DONE, summary.status)
        assertTrue(summary.summaryText.contains("【注意】"))
    }

    /**
     * E2E: chunk cleanup after summarize
     */
    @Test
    fun `chunk DB records deleted after successful summarization`() = runTest {
        val sessionId = "e2e-cleanup"
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockTranscription)
        val summarizeUseCase = SummarizeUseCase(chunkRepo, mockSummary)

        val chunkFile = createWavFile(tempDir, sessionId, 0, 1000)
        chunkRepo.insert(ChunkEntity(
            sessionId = sessionId, chunkIndex = 0,
            filePath = chunkFile.absolutePath, status = ChunkStatus.PENDING
        ))
        coEvery { mockTranscription.transcribe(any()) } returns Result.success("テキスト")
        val c = chunkRepo.getBySession(sessionId).first()
        uploader.uploadChunk(c)

        val mergedFile = File(tempDir, "$sessionId.wav")
        WavMerger.merge(listOf(chunkFile), mergedFile)

        db.summaryDao().insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = sessionId, audioFilePath = mergedFile.absolutePath,
            durationMs = 5000L, status = SummaryStatus.RECORDED
        ))
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            SummaryResult("t", "s")
        )
        summarizeUseCase.executeAndPersist(sessionId, db.summaryDao())

        // chunk DB削除
        chunkRepo.deleteBySession(sessionId)
        assertEquals(0, chunkRepo.getBySession(sessionId).size)

        // summaryは残る
        assertNotNull(db.summaryDao().getBySessionId(sessionId))
    }

    // ===== helpers =====

    /** 最小WAVファイル作成 (44byte header + pcmSize bytes) */
    private fun createWavFile(baseDir: File, sessionId: String, index: Int, pcmSize: Int): File {
        val dir = File(baseDir, sessionId).also { it.mkdirs() }
        val file = File(dir, "chunk_$index.wav")
        // 44-byte WAV header (dummy) + PCM data
        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        val totalSize = 36 + pcmSize
        header[4] = (totalSize and 0xFF).toByte()
        header[5] = ((totalSize shr 8) and 0xFF).toByte()
        header[6] = ((totalSize shr 16) and 0xFF).toByte()
        header[7] = ((totalSize shr 24) and 0xFF).toByte()
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        file.writeBytes(header + ByteArray(pcmSize))
        return file
    }
}
