package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.BackupRestoreUseCase
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupOrphansTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var backupRestoreUseCase: BackupRestoreUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var summariesFlow: MutableStateFlow<List<SummaryEntity>>
    private lateinit var filesDir: File
    private lateinit var recordingsDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chunkRepository = mockk<ChunkRepository>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
        summaryDao = mockk<SummaryDao>(relaxed = true)
        deleteSummaryUseCase = mockk<DeleteSummaryUseCase>(relaxed = true)
        backupRestoreUseCase = mockk<BackupRestoreUseCase>(relaxed = true)
        application = mockk<Application>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        summariesFlow = MutableStateFlow(emptyList())
        every { chunkRepository.getChunksFlow(any()) } returns chunksFlow
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { recordingController.currentSessionId } returns null
        every { summaryDao.observeAll() } returns summariesFlow
        coEvery { recordingController.awaitReady() } returns Unit
        coEvery { summaryDao.getAll() } returns emptyList()

        filesDir = tempFolder.newFolder("files")
        recordingsDir = File(filesDir, "recordings")
        recordingsDir.mkdirs()
        every { application.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, summaryDao,
        deleteSummaryUseCase, backupRestoreUseCase, application, savedStateHandle
    )

    // ===== cleanupOrphanFiles exclusion logic =====

    @Test
    fun `orphan wav file is deleted when not in known sessions`() {
        // 認識されないセッションのwavファイル
        File(recordingsDir, "orphan-session.wav").writeText("dummy")

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns null

        createViewModel()
        Thread.sleep(300) // withContext(IO) completion

        assertFalse(File(recordingsDir, "orphan-session.wav").exists())
    }

    @Test
    fun `orphan directory is deleted when not in known sessions`() {
        // 認識されないセッションのディレクトリ
        val orphanDir = File(recordingsDir, "orphan-session")
        orphanDir.mkdirs()
        File(orphanDir, "chunk_0.wav").writeText("dummy")

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns null

        createViewModel()
        Thread.sleep(300) // withContext(IO) completion

        assertFalse(orphanDir.exists())
    }

    @Test
    fun `wav file for known session is preserved`() {
        val knownFile = File(recordingsDir, "known-session.wav")
        knownFile.writeText("dummy")

        coEvery { summaryDao.getAll() } returns listOf(
            SummaryEntity(sessionId = "known-session", audioFilePath = knownFile.absolutePath, durationMs = 1000L)
        )
        every { recordingController.currentSessionId } returns null

        createViewModel()

        assertTrue(knownFile.exists())
    }

    @Test
    fun `directory for known session is preserved`() {
        val knownDir = File(recordingsDir, "known-session")
        knownDir.mkdirs()
        File(knownDir, "chunk_0.wav").writeText("dummy")

        coEvery { summaryDao.getAll() } returns listOf(
            SummaryEntity(sessionId = "known-session", audioFilePath = "/f.wav", durationMs = 1000L)
        )
        every { recordingController.currentSessionId } returns null

        createViewModel()

        assertTrue(knownDir.exists())
    }

    @Test
    fun `current recording session is excluded from cleanup`() {
        val activeFile = File(recordingsDir, "active-session.wav")
        activeFile.writeText("dummy")

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns "active-session"

        createViewModel()

        assertTrue(activeFile.exists())
    }

    @Test
    fun `current recording session directory is excluded from cleanup`() {
        val activeDir = File(recordingsDir, "active-session")
        activeDir.mkdirs()
        File(activeDir, "chunk_0.wav").writeText("dummy")

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns "active-session"

        createViewModel()

        assertTrue(activeDir.exists())
    }

    @Test
    fun `non-wav files are not deleted`() {
        val txtFile = File(recordingsDir, "readme.txt")
        txtFile.writeText("info")

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns null

        createViewModel()

        assertTrue(txtFile.exists())
    }

    @Test
    fun `cleanup does nothing when recordings directory does not exist`() {
        recordingsDir.deleteRecursively()

        coEvery { summaryDao.getAll() } returns emptyList()
        every { recordingController.currentSessionId } returns null

        // クラッシュしない
        createViewModel()

        assertFalse(recordingsDir.exists())
    }

    @Test
    fun `mixed orphan and known files - only orphans deleted`() {
        // known
        val knownFile = File(recordingsDir, "known.wav")
        knownFile.writeText("keep")
        // orphan
        val orphanFile = File(recordingsDir, "orphan.wav")
        orphanFile.writeText("delete")

        coEvery { summaryDao.getAll() } returns listOf(
            SummaryEntity(sessionId = "known", audioFilePath = knownFile.absolutePath, durationMs = 1000L)
        )
        every { recordingController.currentSessionId } returns null

        createViewModel()
        Thread.sleep(300) // withContext(IO) completion

        assertTrue(knownFile.exists())
        assertFalse(orphanFile.exists())
    }
}
