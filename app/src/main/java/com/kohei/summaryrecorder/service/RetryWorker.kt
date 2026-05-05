package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: TranscriptionUploader
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val failedChunkCount = uploader.getFailedChunkCount()
        if (failedChunkCount == 0) return Result.success()

        val processedCount = uploader.retryFailedChunks()
        return if (processedCount < failedChunkCount) Result.retry() else Result.success()
    }
}
