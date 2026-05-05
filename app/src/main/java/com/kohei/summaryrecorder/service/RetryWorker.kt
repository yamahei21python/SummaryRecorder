package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.service.TranscriptionUploader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: TranscriptionUploader
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        uploader.retryFailedChunks()
        val remainingFailedCount = uploader.getFailedChunkCount()
        return if (remainingFailedCount > 0) Result.retry() else Result.success()
    }
}
