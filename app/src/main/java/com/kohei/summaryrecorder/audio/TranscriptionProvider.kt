package com.kohei.summaryrecorder.audio

import java.io.File

/** 文字起こしAPI抽象化。本番=Groq, E2E=Mock */
fun interface TranscriptionProvider {
    suspend fun transcribe(file: File): Result<String>
}
