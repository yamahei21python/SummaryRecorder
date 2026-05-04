package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.di.ServiceLocator

/**
 * WorkManager定期再送Worker。
 *
 * 機能:
 * - FAILEDチャンクを取得 → UPLOADING → DONE/FAILED 再送
 * - ファイル消失時: セッション全削除（ゾンビレコード防止）
 * - 15分間隔・ネットワーク接続時のみ実行（RecordingServiceでスケジュール）
 * - 冪等: doWork()複数回実行で副作用なし（FAILED時のみ処理）
 *
 * TODO: Phase2で@HiltWorker移行
 */
class RetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val uploader by lazy {
        val dao = ServiceLocator.database.chunkDao()
        TranscriptionUploader(dao, ServiceLocator.transcriptionProvider)
    }

    override suspend fun doWork(): Result {
        uploader.retryFailedChunks()
        return Result.success()
    }
}
