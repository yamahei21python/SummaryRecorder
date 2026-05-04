package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.audio.TranscriptionProvider
import java.io.File
import javax.inject.Inject

/**
 * 文字起こしアップロード共通ロジック。
 * RecordingService（即時）とRetryWorker（再送）の重複を統合。
 *
 * 責務:
 * - 単一チャンク: UPLOADING → transcribe → DONE/FAILED
 * - 失敗チャンク一括再送: FAILED一覧 → セッション単位で処理
 * - ファイル消失検知: セッション全削除
 */
class TranscriptionUploader(
    private val dao: ChunkDao,
    private val transcriptionProvider: TranscriptionProvider
) {

    /**
     * 単一チャンクをアップロード。
     * RecordingServiceのonChunkRecorded → uploadChunkフロー。
     */
    suspend fun uploadChunk(chunk: ChunkEntity): Result<String> {
        dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)

        val file = File(chunk.filePath)
        val result = transcriptionProvider.transcribe(file)

        if (result.isSuccess) {
            val text = result.getOrThrow()
            dao.updateStatus(chunk.id, ChunkStatus.DONE, text)
            file.delete()
        } else {
            dao.updateStatus(chunk.id, ChunkStatus.FAILED)
        }

        return result
    }

    /**
     * 失敗チャンクを一括再送。
     * RetryWorkerのdoWorkロジック。
     *
     * @return 処理済みセッション数
     */
    suspend fun retryFailedChunks(): Int {
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)
        val bySession = failedChunks.groupBy { it.sessionId }
        var processedCount = 0

        bySession.forEach { (sessionId, chunks) ->
            chunks.forEach { chunk ->
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    dao.deleteBySession(sessionId)
                    return@forEach
                }

                dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)

                val result = transcriptionProvider.transcribe(file)
                if (result.isSuccess) {
                    dao.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
                    file.delete()
                    processedCount++
                } else {
                    dao.updateStatus(chunk.id, ChunkStatus.FAILED)
                }
            }
        }

        return processedCount
    }
}
