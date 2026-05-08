package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RecordingManager: audioProvider.start()失敗時の挙動検証。
 *
 * startRecording内でrecorder.start()がIllegalStateExceptionを投げた時:
 * - recorderがnullにリセットされる（再試行可能）
 * - 例外が呼び出し元に伝播する
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManagerStartFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val tempDir: File by lazy { tempFolder.root }

    private lateinit var testScope: TestScope

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
        val manager = RecordingManager(testScope)

        assertFailsWith<IllegalStateException> {
            manager.startRecording("sess", tempDir, failingProvider)
        }
    }

    @Test
    fun `startRecording resets recorder on failure`() = runTest {
        val manager = RecordingManager(testScope)

        try {
            manager.startRecording("sess", tempDir, failingProvider)
        } catch (_: IllegalStateException) {
            // 期待される例外
        }

        // recorderがnull → stopRecordingが例外なく完了する
        val result = manager.stopRecording()
        assertNull(result)
    }

    @Test
    fun `startRecording can be retried after failure`() = runTest {
        val manager = RecordingManager(testScope)

        // 1回目: 失敗
        try {
            manager.startRecording("sess", tempDir, failingProvider)
        } catch (_: IllegalStateException) {
            // 期待される例外
        }

        // recorderがnullであることを確認
        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        assertNull(recorderField.get(manager), "失敗後recorderがnull")

        // 2回目: 成功するproviderで再試行
        manager.startRecording("sess", tempDir, successProvider)
        advanceUntilIdle()

        // recorderが非nullであることを確認
        assertNotNull(recorderField.get(manager), "再試行後recorderが非null")

        // クリーンアップ
        manager.stopRecording()
    }
}
