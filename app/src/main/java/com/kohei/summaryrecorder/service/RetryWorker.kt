package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.di.ServiceLocator
import java.io.File

/**
 * WorkManager定期再送Worker（Phase 4実装完了）。
 *
 * 機能:
 * - FAILEDチャンクを取得 → UPLOADING → DONE/FAILED 再送
 * - ファイル消失時: セッション全削除（ゾンビレコード防止）
 * - 15分間隔・ネットワーク接続時のみ実行（RecordingServiceでスケジュール）
 * - 冪等: doWork()複数回実行で副作用なし（FAILED時のみ処理）
 */
class RetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val dao by lazy { ServiceLocator.database.chunkDao() }
    private val transcriptionRepo: TranscriptionRepository by lazy {
        ServiceLocator.transcriptionRepository
    }

    override suspend fun doWork(): Result {
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)

        val bySession = failedChunks.groupBy { it.sessionId }

        bySession.forEach { (sessionId, chunks) ->
            chunks.forEach { chunk ->
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    // ファイル消失 → セッション全削除
                    dao.deleteBySession(sessionId)
                    return@forEach
                }

                dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)

                val result = transcriptionRepo.transcribe(file)
                if (result.isSuccess) {
                    dao.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
                    file.delete()
                } else {
                    dao.updateStatus(chunk.id, ChunkStatus.FAILED)
                }
            }
        }

        return Result.success()
    }
}
