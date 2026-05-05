package com.kohei.summaryrecorder.service
 
import kotlinx.coroutines.ExperimentalCoroutinesApi

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.service.TranscriptionUploader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RecordingManager: 録音ライフサイクル + チャンクコールバック検証。
 *
 * 検証項目:
 * - start/stopでrecorderが正しく管理される
 * - onChunkRecorded: insert→uploadの正常系
 * - onChunkRecorded: insert失敗時の耐性
 * - onChunkRecorded: upload失敗時の耐性（クラッシュなし）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var testScope: TestScope
    private val mockRepo = mockk<ChunkRepository>(relaxed = true)
    private val mockUploader = mockk<TranscriptionUploader>(relaxed = true)

    @BeforeEach
    fun setUp() {
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
        unmockkAll()
    }

    /** 指定バイト数分のPCMデータを提供後、即終了するAudioProvider */
    private fun createProvider(totalBytes: Long): AudioProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun stop() {}
        override fun release() {}
        private var remaining = totalBytes
        override fun read(buffer: ShortArray, size: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(size, remaining.toInt() / 2)
            for (i in 0 until toRead) buffer[i] = 0
            remaining -= toRead * 2L
            return toRead
        }
    }

    /** 何もしないAudioProvider（writeTestPcmData使用時用） */
    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int = -1
        override fun stop() {}
        override fun release() {}
    }

    // ===== start/stop ライフサイクル =====
    @Test
    fun `startRecording creates recorder`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)
        manager.startRecording("sess", tempDir, 1024L, noopProvider)

        // recorderRef != null をリフレクションで確認
        val recorderField = RecordingManager::class.java.getDeclaredField("recorderRef")
        recorderField.isAccessible = true
        val ref = recorderField.get(manager) as java.util.concurrent.atomic.AtomicReference<*>
        assertNotNull(ref.get())
    }

    @Test
    fun `stopRecording clears recorder`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)
        manager.startRecording("sess", tempDir, 1024L, noopProvider)
        manager.stopRecording()

        val recorderField = RecordingManager::class.java.getDeclaredField("recorderRef")
        recorderField.isAccessible = true
        val ref = recorderField.get(manager) as java.util.concurrent.atomic.AtomicReference<*>
        assertNull(ref.get())
    }

    // ===== onChunkRecorded 正常系 =====
    @Test
    fun `onChunkRecorded inserts and uploads`() = runTest {
        coEvery { mockRepo.insert(any()) } returns 1L
        coEvery { mockUploader.uploadChunk(any()) } returns Result.success("transcribed")

        val manager = RecordingManager(mockRepo, mockUploader, this)
        // chunkSize=4バイト、provider=4バイト分→1チャンク完成
        manager.startRecording("sess", tempDir, 4L, createProvider(4L))

        advanceUntilIdle()

        coVerify(exactly = 2) { mockRepo.insert(any()) }
        coVerify(exactly = 2) { mockUploader.uploadChunk(any()) }
    }

    // ===== onChunkRecorded 異常系: insert失敗 =====
    @Test
    fun `onChunkRecorded handles insert failure gracefully`() = runTest {
        coEvery { mockRepo.insert(any()) } throws RuntimeException("DB error")

        val manager = RecordingManager(mockRepo, mockUploader, this)
        manager.startRecording("sess", tempDir, 4L, createProvider(4L))

        advanceUntilIdle()

        // insert失敗 → uploadは呼ばれない
        coVerify(exactly = 0) { mockUploader.uploadChunk(any()) }
    }

    // ===== onChunkRecorded 異常系: upload失敗 =====
    @Test
    fun `onChunkRecorded handles upload failure gracefully`() = runTest {
        coEvery { mockRepo.insert(any()) } returns 1L
        coEvery { mockUploader.uploadChunk(any()) } returns Result.failure(
            java.io.IOException("network error")
        )

        val manager = RecordingManager(mockRepo, mockUploader, this)
        manager.startRecording("sess", tempDir, 4L, createProvider(4L))

        advanceUntilIdle()

        // uploadは呼ばれるが、結果がfailureでもクラッシュしない
        coVerify(exactly = 2) { mockRepo.insert(any()) }
        coVerify(exactly = 2) { mockUploader.uploadChunk(any()) }
    }

    @Test
    fun `sessionId race condition is prevented by local capture`() = runTest {
        coEvery { mockRepo.insert(any()) } returns 1L
        coEvery { mockUploader.uploadChunk(any()) } returns Result.success("transcribed")

        val manager = RecordingManager(mockRepo, mockUploader, this)
        
        // Start session 1
        manager.startRecording("sess1", tempDir, 4L, createProvider(4L))
        advanceUntilIdle()
        manager.stopRecording()
        
        // Start session 2
        manager.startRecording("sess2", tempDir, 4L, createProvider(4L))
        advanceUntilIdle()
        manager.stopRecording()
        
        // Verify that chunks are inserted with correct session IDs (2 chunks each: 1 data + 1 empty last)
        coVerify(exactly = 2) { mockRepo.insert(match { it.sessionId == "sess1" }) }
        coVerify(exactly = 2) { mockRepo.insert(match { it.sessionId == "sess2" }) }
    }
}
