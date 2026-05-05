package com.kohei.summaryrecorder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    val title: String = "",

    @ColumnInfo(name = "summary_text")
    val summaryText: String = "",

    @ColumnInfo(name = "transcription_text")
    val transcriptionText: String = "",

    @ColumnInfo(name = "audio_file_path")
    val audioFilePath: String = "",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    val status: SummaryStatus = SummaryStatus.RECORDED,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
