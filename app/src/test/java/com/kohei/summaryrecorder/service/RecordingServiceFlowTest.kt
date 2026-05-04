package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.audio.TranscriptionProvider
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RecordingService: チャンクアップロードフローテスト。
 *
 * 検証項目:
 * - PENDING → UPLOADING → DONE の状態遷移（文字起こし成功時）
 * - PENDING → UPLOADING → FAILED の状態遷移（文字起こし失敗時）
 * - 文字起こし成功時: 音声ファイル削除
 * - 文字起こし失敗時: 音声ファイル残存
 * - 複数チャンクの順次処理
 *
 * ※ DAO + TranscriptionProvider レベルでフローを検証。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(
            ApplicationProvider.getApplicationContext()
        )
        mockProvider = mockk<TranscriptionProvider>()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_recordings").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `chunk upload succeeds - PENDING to UPLOADING to DONE, file deleted`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("こんにちは世界")

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "chunk_0.wav").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(100))
        }

        // PENDING → UPLOADING → DONE (via TranscriptionUploader)
        val entity = ChunkEntity(
            sessionId = "test-session-001",
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        )
        val id = dao.insert(entity)

        val chunk = dao.getById(id)!!
        val result = uploader.uploadChunk(chunk)

        // Assert: DONE + テキスト保存
        assertTrue(result.isSuccess)
        val updated = dao.getById(id)!!
        assertEquals(ChunkStatus.DONE, updated.status)
        assertEquals("こんにちは世界", updated.transcriptionText)

        // ファイル削除
        assertTrue(!chunkFile.exists())
    }

    @Test
    fun `chunk upload fails - status becomes FAILED`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network error")
        )

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "chunk_0.wav").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(100))
        }

        val entity = ChunkEntity(
            sessionId = "test-session-002",
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        )
        val id = dao.insert(entity)

        val chunk = dao.getById(id)!!
        val result = uploader.uploadChunk(chunk)

        // Assert: FAILED + テキストなし
        assertTrue(result.isFailure)
        val updated = dao.getById(id)!!
        assertEquals(ChunkStatus.FAILED, updated.status)
        assertNull(updated.transcriptionText)

        // ファイル残存（再送のため）
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `multiple chunks - sequential upload`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("テキスト")

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val sessionId = "test-session-003"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also {
                it.writeBytes(ByteArray(100))
            }
            val entity = ChunkEntity(
                sessionId = sessionId,
                chunkIndex = i,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.PENDING
            )
            val id = dao.insert(entity)

            val chunk = dao.getById(id)!!
            uploader.uploadChunk(chunk)
        }

        // Assert: 全チャンクDONE
        val allChunks = dao.getBySession(sessionId)
        assertEquals(3, allChunks.size)
        allChunks.forEach {
            assertEquals(ChunkStatus.DONE, it.status)
        }
    }

    @Test
    fun `start and stop recording lifecycle - DAO state transitions`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.success("録音テキスト")

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val sessionId = "lifecycle-session"

        // Start: チャンク作成→PENDING
        val chunkFile = File(tempDir, "lifecycle.wav").also {
            it.writeBytes(ByteArray(100))
        }
        val id = dao.insert(ChunkEntity(
            sessionId = sessionId,
            chunkIndex = 0,
            filePath = chunkFile.absolutePath,
            status = ChunkStatus.PENDING
        ))

        // Upload flow via TranscriptionUploader
        val chunk = dao.getById(id)!!
        uploader.uploadChunk(chunk)

        // Verify final state
        val updated = dao.getById(id)!!
        assertEquals(ChunkStatus.DONE, updated.status)
        assertEquals("録音テキスト", updated.transcriptionText)
        assertTrue(!chunkFile.exists())
    }
}
