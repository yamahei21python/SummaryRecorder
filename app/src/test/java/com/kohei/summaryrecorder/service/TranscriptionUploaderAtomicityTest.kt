package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscriptionUploaderAtomicityTest {

    private lateinit var mockRepo: ChunkRepository
    private lateinit var mockProvider: TranscriptionProvider
    private lateinit var uploader: TranscriptionUploader

    @Before
    fun setUp() {
        mockRepo = mockk(relaxed = true)
        mockProvider = mockk()
        uploader = TranscriptionUploader(mockRepo, mockProvider)
    }

    @Test
    fun `transcribe exception after UPLOADING status properly reverts to FAILED`() = runTest {
        val chunk = ChunkEntity("s", 0, "/path.wav", ChunkStatus.PENDING, id = 1L)
        val file = File("/path.wav")
        
        coEvery { mockProvider.transcribe(any()) } throws RuntimeException("Network Error")

        uploader.uploadChunk(chunk)

        // 1. UPLOADING に更新される
        coVerify(ordering = Ordering.SEQUENCE) {
            mockRepo.updateStatus(1L, ChunkStatus.UPLOADING, null, any())
            // 2. 失敗後に FAILED に戻る
            mockRepo.updateStatus(1L, ChunkStatus.FAILED, null, any())
        }
    }
}
