package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class RecordingManagerTest {

    @TempDir
    lateinit var tempDir: File

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mockRepo = mockk<ChunkRepository>(relaxed = true)
    private val mockUploader = mockk<TranscriptionUploader>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // リラックスモックは各テストで上書き可能
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
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

        // recorder != null をリフレクションで確認
        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        assertNotNull(recorderField.get(manager))
    }

    @Test
    fun `stopRecording clears recorder`() = runTest {
        val manager = RecordingManager(mockRepo, mockUploader, testScope)
        manager.startRecording("sess", tempDir, 1024L, noopProvider)
        manager.stopRecording()

        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        assertNull(recorderField.get(manager))
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

        coVerify(exactly = 1) { mockRepo.insert(match { entity ->
            entity.sessionId == "sess" && entity.chunkIndex == 0 && entity.status == ChunkStatus.PENDING
        }) }
        coVerify(exactly = 1) { mockUploader.uploadChunk(match { it.id == 1L }) }
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
        coVerify(exactly = 1) { mockRepo.insert(any()) }
        coVerify(exactly = 1) { mockUploader.uploadChunk(any()) }
    }
}
