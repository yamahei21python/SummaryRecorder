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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TranscriptionUploader: ネットワークエラー・API制約テスト（Phase 4）。
 *
 * 検証項目:
 * - IOException（ネットワーク断）→ FAILED維持
 * - HTTP 401（認証エラー）→ FAILED維持
 * - HTTP 429（レート制限）→ FAILED維持
 * - 混在シナリオ: 成功と失敗が混ざったチャンク群
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RetryWorkerNetworkTest {

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
            .filesDir.resolve("test_retry_network").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `IOException - network down, stays FAILED`() = runTest {
        // Arrange
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network is unreachable")
        )

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "net_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(
            ChunkEntity(
                sessionId = "net-session-001",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        uploader.retryFailedChunks()

        // Assert: FAILEDのまま、ファイル残存
        val failed = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(1, failed.size)
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `HTTP 401 - auth error, stays FAILED`() = runTest {
        // Arrange
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            HttpException(
                "Unauthorized".toResponseBody(401)
            )
        )

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "auth_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(
            ChunkEntity(
                sessionId = "auth-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        uploader.retryFailedChunks()

        // Assert: FAILEDのまま
        assertEquals(1, dao.getByStatus(ChunkStatus.FAILED).size)
        assertEquals(0, dao.getByStatus(ChunkStatus.DONE).size)
    }

    @Test
    fun `HTTP 429 - rate limited, stays FAILED`() = runTest {
        // Arrange
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            HttpException(
                "Too Many Requests".toResponseBody(429)
            )
        )

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val chunkFile = File(tempDir, "rate_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        dao.insert(
            ChunkEntity(
                sessionId = "rate-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        // Act
        uploader.retryFailedChunks()

        // Assert: FAILEDのまま（次回WorkManager周期で再送）
        assertEquals(1, dao.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `mixed results - some succeed some fail`() = runTest {
        // Arrange: 3チャンク、最初と3番目成功、2番目失敗
        val callCount = mutableListOf<Int>()
        coEvery { mockProvider.transcribe(any<File>()) } answers {
            val file = firstArg<File>()
            val index = file.nameWithoutExtension.substringAfter("chunk_").toInt()
            callCount.add(index)
            if (index == 1) {
                Result.failure(java.io.IOException("timeout"))
            } else {
                Result.success("テキスト$index")
            }
        }

        val dao = db.chunkDao()
        val uploader = TranscriptionUploader(dao, mockProvider)
        val sessionId = "mixed-session"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            dao.insert(
                ChunkEntity(
                    sessionId = sessionId,
                    chunkIndex = i,
                    filePath = chunkFile.absolutePath,
                    status = ChunkStatus.FAILED
                )
            )
        }

        // Act
        uploader.retryFailedChunks()

        // Assert
        val done = dao.getByStatus(ChunkStatus.DONE)
        val failed = dao.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, done.size)
        assertEquals(1, failed.size)
        assertEquals(1, failed[0].chunkIndex) // chunk_1 が FAILED
    }
}

// テスト用ヘルパー: HTTPエラーResponse生成
private fun String.toResponseBody(code: Int): retrofit2.Response<Any> =
    retrofit2.Response.error(code, this.toResponseBody("text/plain".toMediaType()))
