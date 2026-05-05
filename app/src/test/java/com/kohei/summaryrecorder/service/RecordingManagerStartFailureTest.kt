package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RecordingManager: audioProvider.start()失敗時の挙動検証。
 *
 * startRecording内でrecorder.start()がIllegalStateExceptionを投げた時:
 * - recorderRefがnullにリセットされる（再試行可能）
 * - 例外が呼び出し元に伝播する
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManagerStartFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val tempDir: File by lazy { tempFolder.root }

    private lateinit var testScope: TestScope
    private val mockRepo = mockk<ChunkRepository>(relaxed = true)
    private val mockUploader = mockk<TranscriptionUploader>(relaxed = true)

    @Before
    fun setUp() {
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        testScope.cancel()
        unmockkAll()
    }

    /** start()がfalseを返すprovider */
    private val failingProvider = object : AudioProvider {
        override fun start(): Boolean = false
        override fun stop() {}
        override fun release() {}
        override fun read(buffer: ShortArray, size: Int): Int = -1
    }

    /** start()がtrueを返す正常provider */
    private val successProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun stop() {}
        override fun release() {}
        override fun read(buffer: ShortArray, size: Int): Int = -1
    }

    @Test
    fun `startRecording throws when audioProvider start fails`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)

        assertFailsWith<IllegalStateException> {
            manager.startRecording("sess", tempDir, 1024L, failingProvider)
        }
    }

    @Test
    fun `startRecording resets recorderRef on failure`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)

        try {
            manager.startRecording("sess", tempDir, 1024L, failingProvider)
        } catch (_: IllegalStateException) {
            // 期待される例外
        }

        // recorderRefがnull → stopRecordingが例外なく完了する
        manager.stopRecording() // recorderRef==nullなので何もしない
    }

    @Test
    fun `startRecording can be retried after failure`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)

        // 1回目: 失敗
        try {
            manager.startRecording("sess", tempDir, 1024L, failingProvider)
        } catch (_: IllegalStateException) {
            // 期待される例外
        }

        // 2回目: 成功するproviderで再試行
        manager.startRecording("sess", tempDir, 1024L, successProvider)
        advanceUntilIdle()

        // リフレクションでrecorderRefが非nullであることを確認
        val recorderField = RecordingManager::class.java.getDeclaredField("recorderRef")
        recorderField.isAccessible = true
        val ref = recorderField.get(manager) as java.util.concurrent.atomic.AtomicReference<*>
        assertTrue(ref.get() != null, "再試行後recorderRefが非null")

        // クリーンアップ
        manager.stopRecording()
    }
}
