package com.kohei.summaryrecorder.usecase

import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class DeleteSummaryUseCaseTest {

    private lateinit var mockDao: SummaryDao
    private lateinit var useCase: DeleteSummaryUseCase
    private var tempFile: File? = null

    @Before
    fun setUp() {
        mockDao = mockk<SummaryDao>(relaxed = true)
        useCase = DeleteSummaryUseCase(mockDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempFile?.delete()
    }

    @Test
    fun `execute deletes WAV file and DB record`() = runTest {
        tempFile = File.createTempFile("test_audio", ".wav")
        assertTrue(tempFile!!.exists())

        useCase.execute("s1", tempFile!!.absolutePath)

        assertTrue(!tempFile!!.exists(), "WAVファイルが削除されていること")
        coVerify { mockDao.delete("s1") }
    }

    @Test
    fun `execute handles missing file gracefully`() = runTest {
        useCase.execute("s2", "/nonexistent/path/audio.wav")

        // ファイルが存在しなくてもクラッシュしない
        coVerify { mockDao.delete("s2") }
    }

    @Test
    fun `execute always deletes DB record even if file missing`() = runTest {
        useCase.execute("s3", "/missing.wav")

        coVerify(exactly = 1) { mockDao.delete("s3") }
    }
}
