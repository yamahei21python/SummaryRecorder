package com.kohei.summaryrecorder.data.repository

import android.util.Log
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SessionHistory
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

class ChunkRepositoryImpl @Inject constructor(
    private val dao: ChunkDao
) : ChunkRepository {
    override suspend fun insert(chunk: ChunkEntity): Long = dao.insert(chunk)
    override suspend fun getBySession(sessionId: String): List<ChunkEntity> = dao.getBySession(sessionId)
    override suspend fun getByStatus(status: ChunkStatus): List<ChunkEntity> = dao.getByStatus(status)
    override suspend fun countByStatus(sessionId: String, status: ChunkStatus): Int =
        dao.countByStatus(sessionId, status)
    override suspend fun updateStatus(id: Long, newStatus: ChunkStatus, text: String?, now: Long) =
        dao.updateStatus(id, newStatus, text, now)
    
    override suspend fun casToUploading(id: Long, now: Long): Int = 
        dao.casStatus(id, listOf(ChunkStatus.FAILED, ChunkStatus.PENDING), ChunkStatus.UPLOADING, now)
    
    override suspend fun casToFailed(id: Long, now: Long): Int =
        dao.casStatus(id, listOf(ChunkStatus.UPLOADING, ChunkStatus.PENDING, ChunkStatus.FAILED), ChunkStatus.FAILED, now)
    
    override suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)
    override suspend fun deleteById(id: Long) = dao.deleteById(id)
    
    override suspend fun resetStuckUploads(now: Long) =
        dao.resetStatusBulk(listOf(ChunkStatus.UPLOADING), ChunkStatus.FAILED, now)
    
    override fun observeBySession(sessionId: String): Flow<List<ChunkEntity>> = dao.observeBySession(sessionId)

    override suspend fun getSessionHistory(): List<SessionHistory> = dao.getSessionHistory()

    override suspend fun getFilePathsBySession(sessionId: String): List<String> = dao.getFilePathsBySession(sessionId)

    override suspend fun deleteSessionData(sessionId: String) {
        val filePaths = getFilePathsBySession(sessionId)
        filePaths.forEach { path ->
            val file = File(path)
            if (file.exists() && !file.delete()) {
                Log.w("ChunkRepository", "Failed to delete: $path")
            }
        }
        deleteBySession(sessionId)
    }
}
