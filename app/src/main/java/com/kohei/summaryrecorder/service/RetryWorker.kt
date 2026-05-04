package com.kohei.summaryrecorder.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.di.ServiceLocator
import java.io.File

/**
 * WorkManager定期再送Worker（Phase 4で本格実装）。
 *
 * 現状: コンパイル通すためのスタブ。
 * Phase 4で以下を実装:
 * - FAILED → UPLOADING → DONE/FAILED 再送
 * - ファイル消失時セッション全削除
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
