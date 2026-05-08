package com.kohei.summaryrecorder.e2e

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.*
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.viewmodel.MainViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * E2E: アプリ再起動時にRECORDED/SUMMARIZING状態のセッションが
 * retryPendingRecordsで再処理されることをreal Room DBで検証。
 *
 * 注意: ViewModel init内に while(true) { delay(N) } ループがあるため
 * runTestは使えない。DB操作はrunBlockingで実行。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class AppRestartRetryE2eTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var recordingController: RecordingController
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var filesDir: File

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        recordingController = mockk<RecordingController>(relaxed = true)
        deleteSummaryUseCase = mockk<DeleteSummaryUseCase>(relaxed = true)
        application = mockk<Application>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        filesDir = tempFolder.newFolder("files")
        every { application.filesDir } returns filesDir
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { recordingController.currentSessionId } returns null
        coEvery { recordingController.awaitReady() } returns Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun insertSummary(entity: SummaryEntity) {
        runBlocking { db.summaryDao().insert(entity) }
    }

    private fun getAllSummaries(): List<SummaryEntity> {
        return runBlocking { db.summaryDao().getAll() }
    }

    /**
     * テスト1-3: mock DAOでViewModel initを検証。
     * real DBはデータ準備にのみ使用し、DAO自体はmockに差し替え。
     * spykはreal Room DAOでは動作しないため、mock DAOパターンを採用。
     */

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retryPendingRecords processes RECORDED sessions on app restart`() {
        val summaryDao = mockk<SummaryDao>(relaxed = true)
        val summariesFlow = MutableStateFlow(emptyList<SummaryEntity>())
        every { summaryDao.observeAll() } returns summariesFlow

        coEvery { summaryDao.getByStatus(any()) } returns listOf(
            SummaryEntity(sessionId = "crash-1", status = SummaryStatus.RECORDED, audioFilePath = "/a.wav", durationMs = 1000L)
        )
        coEvery { summaryDao.getAll() } returns emptyList()

        val summarizeUseCase = mockk<SummarizeUseCase>()
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val chunkRepo: ChunkRepository = mockk(relaxed = true)
        every { chunkRepo.getChunksFlow(any()) } returns MutableStateFlow(emptyList())

        MainViewModel(
            chunkRepo, summarizeUseCase, recordingController, summaryDao,
            deleteSummaryUseCase, application, savedStateHandle
        )

        coVerify { summarizeUseCase.executeAndPersist("crash-1", summaryDao) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retryPendingRecords processes SUMMARIZING sessions on app restart`() {
        val summaryDao = mockk<SummaryDao>(relaxed = true)
        val summariesFlow = MutableStateFlow(emptyList<SummaryEntity>())
        every { summaryDao.observeAll() } returns summariesFlow

        coEvery { summaryDao.getByStatus(any()) } returns listOf(
            SummaryEntity(sessionId = "crash-2", status = SummaryStatus.SUMMARIZING, audioFilePath = "/b.wav", durationMs = 2000L)
        )
        coEvery { summaryDao.getAll() } returns emptyList()

        val summarizeUseCase = mockk<SummarizeUseCase>()
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val chunkRepo: ChunkRepository = mockk(relaxed = true)
        every { chunkRepo.getChunksFlow(any()) } returns MutableStateFlow(emptyList())

        MainViewModel(
            chunkRepo, summarizeUseCase, recordingController, summaryDao,
            deleteSummaryUseCase, application, savedStateHandle
        )

        coVerify { summarizeUseCase.executeAndPersist("crash-2", summaryDao) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retryPendingRecords only processes RECORDED and SUMMARIZING not DONE or ERROR`() {
        val summaryDao = mockk<SummaryDao>(relaxed = true)
        val summariesFlow = MutableStateFlow(emptyList<SummaryEntity>())
        every { summaryDao.observeAll() } returns summariesFlow

        coEvery { summaryDao.getByStatus(any()) } returns listOf(
            SummaryEntity(sessionId = "s1", status = SummaryStatus.RECORDED, audioFilePath = "/a.wav", durationMs = 1000L),
            SummaryEntity(sessionId = "s2", status = SummaryStatus.SUMMARIZING, audioFilePath = "/b.wav", durationMs = 2000L)
        )
        coEvery { summaryDao.getAll() } returns emptyList()

        val summarizeUseCase = mockk<SummarizeUseCase>()
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val chunkRepo: ChunkRepository = mockk(relaxed = true)
        every { chunkRepo.getChunksFlow(any()) } returns MutableStateFlow(emptyList())

        MainViewModel(
            chunkRepo, summarizeUseCase, recordingController, summaryDao,
            deleteSummaryUseCase, application, savedStateHandle
        )

        coVerify(exactly = 1) { summarizeUseCase.executeAndPersist("s1", summaryDao) }
        coVerify(exactly = 1) { summarizeUseCase.executeAndPersist("s2", summaryDao) }
    }

    /**
     * テスト4: real SummarizeUseCase + real Room DB でDB内ステータス遷移を検証。
     * これが真のE2Eテスト。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `real executeAndPersist with real DB updates RECORDED to DONE`() {
        // real DBにチャンクとサマリーをinsert
        runBlocking {
            val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
            chunkRepo.insert(ChunkEntity(
                sessionId = "retry-ok", chunkIndex = 0,
                filePath = "/dummy.wav", status = ChunkStatus.DONE,
                transcriptionText = "テキスト"
            ))
            db.summaryDao().insert(SummaryEntity(
                sessionId = "retry-ok", audioFilePath = "/merged.wav",
                durationMs = 5000L, status = SummaryStatus.RECORDED
            ))
        }

        // mock SummaryProvider (API呼び出しのみmock)
        val mockSummary = mockk<com.kohei.summaryrecorder.domain.repository.SummaryProvider>()
        coEvery { mockSummary.summarize(any()) } returns Result.success(
            com.kohei.summaryrecorder.data.model.SummaryResult("再試行タイトル", "再試行要約")
        )
        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val realSummarizeUseCase = SummarizeUseCase(chunkRepo, mockSummary)

        // real summaryDao (spyk不可なので、ViewModelに渡すDAOはmockでobserveAllだけwrap)
        val realSummaryDao = db.summaryDao()
        val summariesFlow = MutableStateFlow(emptyList<SummaryEntity>())
        // real DBのDAOをラップするmock: observeAllのみwrap、他はrealに委譲
        val wrappedDao = mockk<SummaryDao>(relaxed = true)
        every { wrappedDao.observeAll() } returns summariesFlow
        coEvery { wrappedDao.getByStatus(any()) } coAnswers {
            realSummaryDao.getByStatus(firstArg())
        }
        coEvery { wrappedDao.getAll() } coAnswers {
            realSummaryDao.getAll()
        }
        coEvery { wrappedDao.updateStatus(any(), any(), any()) } coAnswers {
            realSummaryDao.updateStatus(firstArg(), secondArg(), thirdArg())
        }
        coEvery { wrappedDao.updateStatusAndContent(any(), any(), any(), any(), any()) } coAnswers {
            realSummaryDao.updateStatusAndContent(
                firstArg(), secondArg(), thirdArg(), arg(3), arg(4)
            )
        }

        val chunkRepoMock: ChunkRepository = mockk(relaxed = true)
        every { chunkRepoMock.getChunksFlow(any()) } returns MutableStateFlow(emptyList())

        MainViewModel(
            chunkRepoMock, realSummarizeUseCase, recordingController, wrappedDao,
            deleteSummaryUseCase, application, savedStateHandle
        )

        // DB検証
        Thread.sleep(500)
        runBlocking {
            val done = realSummaryDao.getBySessionId("retry-ok")!!
            assertEquals(SummaryStatus.DONE, done.status)
            assertEquals("再試行タイトル", done.title)
            assertEquals("再試行要約", done.summaryText)
        }
    }
}
