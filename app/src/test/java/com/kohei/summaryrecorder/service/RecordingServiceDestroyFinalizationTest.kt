package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.recorder.GaplessRecorder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceDestroyFinalizationTest {

    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_finalization").also { it.mkdirs() }
        
        controller = Robolectric.buildService(RecordingService::class.java)
        service = controller.get()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `onDestroy finalizes WAV header before returning`() = runTest {
        // RecordingManagerをモックして、stopRecordingの終了をエミュレート
        val mockManager = mockk<RecordingManager>(relaxed = true)
        
        // Reflectionでモックを差し込み (DIのタイミングを避けるため)
        val field = RecordingService::class.java.getDeclaredField("recordingManager")
        field.isAccessible = true
        field.set(service, mockManager)

        // onDestroyを呼び出し
        controller.destroy()

        // stopRecordingが呼ばれたことを確認
        coVerify(exactly = 1) { mockManager.stopRecording() }
    }
}
