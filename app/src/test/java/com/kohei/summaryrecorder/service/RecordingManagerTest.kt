package com.kohei.summaryrecorder.service
 
import kotlinx.coroutines.ExperimentalCoroutinesApi

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import io.mockk.unmockkAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RecordingManager: 録音ライフサイクル検証。
 *
 * 検証項目:
 * - start/stopでrecorderが正しく管理される
 * - getCurrentSessionIdの正しい返却
 * - stopRecording後のフィールドクリア
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var testScope: TestScope

    @BeforeEach
    fun setUp() {
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
        unmockkAll()
    }

    /** 何もしないAudioProvider（readが即-1を返す） */
    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int = -1
        override fun stop() {}
        override fun release() {}
    }

    // ===== start/stop ライフサイクル =====
    @Test
    fun `startRecording creates recorder`() = runTest {
        val manager = RecordingManager(testScope)
        manager.startRecording("sess", tempDir, noopProvider)

        // recorder != null をリフレクションで確認
        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        assertNotNull(recorderField.get(manager))
    }

    @Test
    fun `stopRecording clears recorder`() = runTest {
        val manager = RecordingManager(testScope)
        manager.startRecording("sess", tempDir, noopProvider)
        manager.stopRecording()

        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        assertNull(recorderField.get(manager))
    }

    @Test
    fun `getCurrentSessionId returns correct id after start`() = runTest {
        val manager = RecordingManager(testScope)
        assertNull(manager.getCurrentSessionId())

        manager.startRecording("sess1", tempDir, noopProvider)
        assertEquals("sess1", manager.getCurrentSessionId())
    }

    @Test
    fun `getCurrentSessionId returns null after stop`() = runTest {
        val manager = RecordingManager(testScope)
        manager.startRecording("sess1", tempDir, noopProvider)
        manager.stopRecording()

        assertNull(manager.getCurrentSessionId())
    }

    @Test
    fun `startRecording returns output file`() = runTest {
        val manager = RecordingManager(testScope)
        val file = manager.startRecording("sess", tempDir, noopProvider)

        assertNotNull(file)
        advanceUntilIdle()
        manager.stopRecording()
    }

    @Test
    fun `stopRecording returns output file`() = runTest {
        val manager = RecordingManager(testScope)
        manager.startRecording("sess", tempDir, noopProvider)
        advanceUntilIdle()

        val file = manager.stopRecording()
        assertNotNull(file)
    }

    @Test
    fun `sessionId updated on second startRecording`() = runTest {
        val manager = RecordingManager(testScope)

        manager.startRecording("sess1", tempDir, noopProvider)
        assertEquals("sess1", manager.getCurrentSessionId())
        advanceUntilIdle()
        manager.stopRecording()

        assertNull(manager.getCurrentSessionId())

        manager.startRecording("sess2", tempDir, noopProvider)
        assertEquals("sess2", manager.getCurrentSessionId())
        advanceUntilIdle()
        manager.stopRecording()

        assertNull(manager.getCurrentSessionId())
    }
}
