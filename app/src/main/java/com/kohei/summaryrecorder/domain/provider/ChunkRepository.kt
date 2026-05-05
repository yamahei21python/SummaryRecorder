package com.kohei.summaryrecorder.domain.provider

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import kotlinx.coroutines.flow.Flow

interface ChunkRepository {
    suspend fun insert(chunk: ChunkEntity): Long
    suspend fun getBySession(sessionId: String): List<ChunkEntity>
    suspend fun getByStatus(status: ChunkStatus): List<ChunkEntity>
    suspend fun updateStatus(id: Long, newStatus: ChunkStatus, text: String? = null, now: Long = System.currentTimeMillis())
    suspend fun deleteBySession(sessionId: String)
    suspend fun deleteById(id: Long)
    suspend fun resetStuckUploads(now: Long = System.currentTimeMillis())
    fun observeBySession(sessionId: String): Flow<List<ChunkEntity>>
}
