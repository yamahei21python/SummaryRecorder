package com.kohei.summaryrecorder.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {

    // ===== CREATE =====

    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    // ===== READ =====

    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun getById(id: Long): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE session_id = :sessionId ORDER BY chunk_index ASC")
    suspend fun getBySession(sessionId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE status = :status")
    suspend fun getByStatus(status: ChunkStatus): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE session_id = :sessionId ORDER BY chunk_index ASC")
    fun observeBySession(sessionId: String): Flow<List<ChunkEntity>>

    @Query("SELECT COUNT(*) FROM chunks WHERE session_id = :sessionId AND status = :status")
    suspend fun countByStatus(sessionId: String, status: ChunkStatus): Int

    // ===== UPDATE =====

    @Query("""
        UPDATE chunks
        SET status = :newStatus,
            transcription_text = :text,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: Long,
        newStatus: ChunkStatus,
        text: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE chunks
        SET status = 'UPLOADING', updated_at = :now
        WHERE id = :id AND status IN ('FAILED', 'PENDING')
    """)
    suspend fun casToUploading(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE chunks
        SET status = 'FAILED', updated_at = :now
        WHERE id = :id AND status = 'UPLOADING'
    """)
    suspend fun casToFailed(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE chunks
        SET status = 'FAILED', updated_at = :now
        WHERE status = 'UPLOADING'
    """)
    suspend fun resetStuckUploads(now: Long = System.currentTimeMillis())

    // ===== DELETE =====

    @Query("DELETE FROM chunks WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM chunks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
