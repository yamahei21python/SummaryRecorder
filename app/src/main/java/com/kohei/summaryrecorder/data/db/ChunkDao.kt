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
        SET status = :targetStatus, updated_at = :now
        WHERE id = :id AND status IN (:sourceStatuses)
    """)
    suspend fun casStatus(
        id: Long,
        sourceStatuses: List<ChunkStatus>,
        targetStatus: ChunkStatus,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE chunks
        SET status = :targetStatus, updated_at = :now
        WHERE status IN (:sourceStatuses)
    """)
    suspend fun resetStatusBulk(
        sourceStatuses: List<ChunkStatus>,
        targetStatus: ChunkStatus,
        now: Long = System.currentTimeMillis()
    )

    // ===== DELETE =====

    @Query("DELETE FROM chunks WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM chunks WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ===== SESSION HISTORY =====

    @Query("""
        SELECT session_id AS sessionId, MIN(created_at) AS createdAt,
               COUNT(*) AS totalChunks,
               SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS doneChunks,
               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedChunks
        FROM chunks GROUP BY session_id ORDER BY MIN(created_at) DESC
    """)
    suspend fun getSessionHistory(): List<SessionHistory>

    @Query("SELECT file_path FROM chunks WHERE session_id = :sessionId")
    suspend fun getFilePathsBySession(sessionId: String): List<String>
}
