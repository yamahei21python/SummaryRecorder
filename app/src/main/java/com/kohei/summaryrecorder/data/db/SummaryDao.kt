package com.kohei.summaryrecorder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {

    // ===== CREATE =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SummaryEntity)

    // ===== READ =====

    @Query("SELECT * FROM summaries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries ORDER BY created_at DESC")
    suspend fun getAll(): List<SummaryEntity>

    @Query("SELECT * FROM summaries WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): SummaryEntity?

    @Query("SELECT * FROM summaries WHERE status IN (:statuses)")
    suspend fun getByStatus(statuses: List<SummaryStatus>): List<SummaryEntity>

    @Query("SELECT COUNT(*) FROM summaries WHERE status = 'DONE' AND is_read = 0")
    suspend fun countUnreadDone(): Int

    // ===== UPDATE =====

    @Query("""
        UPDATE summaries
        SET status = :status,
            error_message = :errorMessage
        WHERE session_id = :sessionId
    """)
    suspend fun updateStatus(
        sessionId: String,
        status: SummaryStatus,
        errorMessage: String? = null
    )

    @Query("""
        UPDATE summaries
        SET status = :status,
            title = :title,
            summary_text = :summaryText,
            transcription_text = :transcriptionText
        WHERE session_id = :sessionId
    """)
    suspend fun updateStatusAndContent(
        sessionId: String,
        status: SummaryStatus,
        title: String,
        summaryText: String,
        transcriptionText: String
    )

    @Query("""
        UPDATE summaries
        SET title = :title
        WHERE session_id = :sessionId
    """)
    suspend fun updateTitle(sessionId: String, title: String)

    @Query("""
        UPDATE summaries
        SET is_read = :isRead
        WHERE session_id = :sessionId
    """)
    suspend fun updateRead(sessionId: String, isRead: Boolean)

    // ===== DELETE =====

    @Query("DELETE FROM summaries WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String)
}
