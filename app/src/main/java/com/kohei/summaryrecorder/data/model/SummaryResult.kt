package com.kohei.summaryrecorder.data.model

/**
 * Gemini が生成する構造化出力。
 * transcriptionText は Groq 側で生成済みのため含めない。
 */
data class SummaryResult(
    val title: String,
    val summaryText: String
)
