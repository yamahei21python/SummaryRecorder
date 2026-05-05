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
        // BUG-005: PeriodicWork で Result.retry() を返すと不規則なリトライループに陥るため、
        // 今回の試行は success で終え、リトライは次回の定期実行スケジュール（15分後等）に委ねる。
        return Result.success()
    }
}
