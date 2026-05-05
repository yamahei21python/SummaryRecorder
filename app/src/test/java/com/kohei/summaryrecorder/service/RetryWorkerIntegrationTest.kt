package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RetryWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var mockUploader: TranscriptionUploader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockUploader = mockk()
    }

    @Test
    fun `doWork returns success when no failed chunks remain`() = runTest {
        coEvery { mockUploader.retryFailedChunks() } returns 0
        coEvery { mockUploader.getFailedChunkCount() } returns 0
        
        val worker = TestListenableWorkerBuilder<RetryWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters): ListenableWorker? {
                    return RetryWorker(appContext, workerParameters, mockUploader)
                }
            })
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }
}
