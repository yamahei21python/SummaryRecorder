package com.kohei.summaryrecorder.data.db

data class SessionHistory(
    val sessionId: String,
    val createdAt: Long,
    val totalChunks: Int,
    val doneChunks: Int,
    val failedChunks: Int
)
