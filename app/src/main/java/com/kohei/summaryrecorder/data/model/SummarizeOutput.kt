package com.kohei.summaryrecorder.data.model

/**
 * SummarizeUseCase の戻り値。
 * Gemini出力 + Groq結合テキストをまとめて呼び出し元に渡す。
 */
data class SummarizeOutput(
    val summaryResult: SummaryResult,
    val transcriptionText: String
)
