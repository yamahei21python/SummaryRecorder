package com.kohei.summaryrecorder.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kohei.summaryrecorder.data.db.SummaryStatus
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only BroadcastReceiver.
 * E2E heavy test用: Groq転写 → Gemini要約のパイプラインを手動トリガーする。
 *
 * 使用方法:
 *   adb shell am broadcast -a com.kohei.summaryrecorder.DEBUG_TRIGGER_PIPELINE
 *
 * 前提: setup_heavy.sh でPENDING chunks + RECORDED summaryがDBに投入済み
 */
class DebugPipelineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.kohei.summaryrecorder.DEBUG_TRIGGER_PIPELINE") return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PipelineEntryPoint::class.java
        )

        val uploader = entryPoint.transcriptionUploader()
        val summarizeUseCase = entryPoint.summarizeUseCase()
        val summaryDao = entryPoint.summaryDao()

        Log.d("DebugPipeline", "Triggered: starting transcription → summarization pipeline")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Groq転写 (PENDING/FAILED chunks → DONE)
                Log.d("DebugPipeline", "Step 1: Transcribing chunks via Groq...")
                val remainingFailed = uploader.retryFailedChunks()
                Log.d("DebugPipeline", "Step 1 done: $remainingFailed remaining failed chunks")

                // Step 2: Gemini要約 (RECORDED/SUMMARIZING summaries → DONE)
                Log.d("DebugPipeline", "Step 2: Summarizing via Gemini...")
                val pending = summaryDao.getByStatus(
                    listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)
                )
                for (entity in pending) {
                    Log.d("DebugPipeline", "Summarizing session: ${entity.sessionId}")
                    summarizeUseCase.executeAndPersist(entity.sessionId, summaryDao)
                }
                Log.d("DebugPipeline", "Step 2 done: ${pending.size} sessions processed")

                Log.d("DebugPipeline", "Pipeline complete!")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DebugPipeline", "Pipeline failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
