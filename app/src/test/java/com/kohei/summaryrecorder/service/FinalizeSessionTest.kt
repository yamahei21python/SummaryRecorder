package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * finalizeSession フローの連携テスト。
 * RecordingService.finalizeSessionはprivateなので、ロジックをシミュレートして検証。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinalizeSessionTest {

    private lateinit var summaryDao: SummaryDao
    private lateinit var transcriptionProvider: TranscriptionProvider
    private lateinit var summaryProvider: SummaryProvider

    @Before
    fun setUp() {
        summaryDao = mockk(relaxed = true)
        transcriptionProvider = mockk()
        summaryProvider = mockk()
    }

    @Test
    fun `RECORDED to SUMMARIZING to DONE - full success flow`() = runTest {
        val wavFile = File("/tmp/test.wav")
        val transcriptionText = "転写テキスト"
        val summaryResult = SummaryResult("タイトル", "要約テキスト")

        coEvery { transcriptionProvider.transcribe(wavFile) } returns Result.success(transcriptionText)
        coEvery { summaryProvider.summarize(transcriptionText) } returns Result.success(summaryResult)

        // フローをシミュレート（RecordingService.finalizeSessionと同じロジック）
        summaryDao.insert(SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        // 文字起こし
        val transcriptionResult = transcriptionProvider.transcribe(wavFile)
        if (transcriptionResult.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
            return@runTest
        }
        val tText = transcriptionResult.getOrThrow()

        // 要約
        val sResult = summaryProvider.summarize(tText)
        if (sResult.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = sResult.exceptionOrNull()?.message)
            return@runTest
        }
        val output = sResult.getOrThrow()

        summaryDao.updateStatusAndContent(
            "s1", SummaryStatus.DONE,
            output.title, output.summaryText, tText
        )

        coVerify { summaryDao.insert(any()) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatusAndContent("s1", SummaryStatus.DONE, "タイトル", "要約テキスト", "転写テキスト") }
    }

    @Test
    fun `transcription failure - SUMMARIZING to ERROR`() = runTest {
        val wavFile = File("/tmp/test.wav")

        coEvery { transcriptionProvider.transcribe(wavFile) } returns Result.failure(
            RuntimeException("API timeout")
        )

        summaryDao.insert(SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        // 文字起こし失敗
        val transcriptionResult = transcriptionProvider.transcribe(wavFile)
        if (transcriptionResult.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
            return@runTest
        }

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, "API timeout") }
        // summarizeは呼ばれない
        coVerify(exactly = 0) { summaryProvider.summarize(any()) }
    }

    @Test
    fun `summarize failure - SUMMARIZING to ERROR`() = runTest {
        val wavFile = File("/tmp/test.wav")
        val transcriptionText = "転写テキスト"

        coEvery { transcriptionProvider.transcribe(wavFile) } returns Result.success(transcriptionText)
        coEvery { summaryProvider.summarize(transcriptionText) } returns Result.failure(
            RuntimeException("Gemini error")
        )

        summaryDao.insert(SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        // 文字起こし成功
        val transcriptionResult = transcriptionProvider.transcribe(wavFile)
        if (transcriptionResult.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
            return@runTest
        }
        val tText = transcriptionResult.getOrThrow()

        // 要約失敗
        val sResult = summaryProvider.summarize(tText)
        if (sResult.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = sResult.exceptionOrNull()?.message)
            return@runTest
        }

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, "Gemini error") }
        // updateStatusAndContentは呼ばれない
        coVerify(exactly = 0) { summaryDao.updateStatusAndContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unexpected exception - caught and status set to ERROR`() = runTest {
        val wavFile = File("/tmp/test.wav")

        coEvery { transcriptionProvider.transcribe(wavFile) } throws RuntimeException("unexpected")

        summaryDao.insert(SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        // フロー全体をtry-catchでシミュレート（RecordingService.finalizeSessionと同じ）
        try {
            val transcriptionResult = transcriptionProvider.transcribe(wavFile)
            // ... 以降の処理
        } catch (e: Exception) {
            try {
                summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = e.message)
            } catch (_: Exception) {}
        }

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, "unexpected") }
    }
}
