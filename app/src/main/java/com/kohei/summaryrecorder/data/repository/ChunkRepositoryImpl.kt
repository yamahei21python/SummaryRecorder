package com.kohei.summaryrecorder.data.repository

import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import kotlinx.coroutines.flow.Flow
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
    override suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)
    override suspend fun deleteById(id: Long) = dao.deleteById(id)
    override suspend fun resetStuckUploads(now: Long) = dao.resetStuckUploads(now)
    override fun observeBySession(sessionId: String): Flow<List<ChunkEntity>> = dao.observeBySession(sessionId)
    override fun getChunksFlow(sessionId: String): Flow<List<ChunkEntity>> = dao.observeBySession(sessionId)
}
