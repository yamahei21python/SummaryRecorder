package com.kohei.summaryrecorder.domain.repository

/** 要約API抽象化。本番=Gemini, E2E=Mock */
fun interface SummaryProvider {
    suspend fun summarize(text: String): Result<String>
}