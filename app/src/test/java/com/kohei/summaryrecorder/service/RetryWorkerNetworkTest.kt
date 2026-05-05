package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.service.TranscriptionUploader
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.ChunkRepositoryImpl
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
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            java.io.IOException("Network is unreachable")
        )

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val chunkFile = File(tempDir, "net_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        chunkRepo.insert(
            ChunkEntity(
                sessionId = "net-session-001",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        val failed = chunkRepo.getByStatus(ChunkStatus.FAILED)
        assertEquals(1, failed.size)
        assertTrue(chunkFile.exists())
    }

    @Test
    fun `HTTP 401 - auth error, stays FAILED`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            HttpException(
                "Unauthorized".toResponseBody(401)
            )
        )

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val chunkFile = File(tempDir, "auth_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        chunkRepo.insert(
            ChunkEntity(
                sessionId = "auth-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        assertEquals(1, chunkRepo.getByStatus(ChunkStatus.FAILED).size)
        assertEquals(0, chunkRepo.getByStatus(ChunkStatus.DONE).size)
    }

    @Test
    fun `HTTP 429 - rate limited, stays FAILED`() = runTest {
        coEvery { mockProvider.transcribe(any<File>()) } returns Result.failure(
            HttpException(
                "Too Many Requests".toResponseBody(429)
            )
        )

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val chunkFile = File(tempDir, "rate_chunk.wav").also { it.writeBytes(ByteArray(100)) }

        chunkRepo.insert(
            ChunkEntity(
                sessionId = "rate-session",
                chunkIndex = 0,
                filePath = chunkFile.absolutePath,
                status = ChunkStatus.FAILED
            )
        )

        uploader.retryFailedChunks()

        assertEquals(1, chunkRepo.getByStatus(ChunkStatus.FAILED).size)
    }

    @Test
    fun `mixed results - some succeed some fail`() = runTest {
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

        val chunkRepo = ChunkRepositoryImpl(db.chunkDao())
        val uploader = TranscriptionUploader(chunkRepo, mockProvider)
        val sessionId = "mixed-session"

        for (i in 0..2) {
            val chunkFile = File(tempDir, "chunk_$i.wav").also { it.writeBytes(ByteArray(100)) }
            chunkRepo.insert(
                ChunkEntity(
                    sessionId = sessionId,
                    chunkIndex = i,
                    filePath = chunkFile.absolutePath,
                    status = ChunkStatus.FAILED
                )
            )
        }

        uploader.retryFailedChunks()

        val done = chunkRepo.getByStatus(ChunkStatus.DONE)
        val failed = chunkRepo.getByStatus(ChunkStatus.FAILED)
        assertEquals(2, done.size)
        assertEquals(1, failed.size)
        assertEquals(1, failed[0].chunkIndex)
    }
}

private fun String.toResponseBody(code: Int): retrofit2.Response<Any> =
    retrofit2.Response.error(code, this.toResponseBody("text/plain".toMediaType()))
