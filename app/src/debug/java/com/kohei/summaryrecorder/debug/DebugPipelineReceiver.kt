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
import java.io.File

/**
 * Debug-only BroadcastReceiver.
 * E2E heavy test用: Groq転写 → Gemini要約のパイプラインを手動トリガーする。
 *
 * 使用方法:
 *   adb shell am broadcast -a com.kohei.summaryrecorder.DEBUG_TRIGGER_PIPELINE
 *
 * 前提: setup_heavy.sh でRECORDED summaryがDBに投入済み
 */
class DebugPipelineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.kohei.summaryrecorder.DEBUG_TRIGGER_PIPELINE") return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PipelineEntryPoint::class.java
        )

        val transcriptionProvider = entryPoint.transcriptionProvider()
        val summaryProvider = entryPoint.summaryProvider()
        val summaryDao = entryPoint.summaryDao()

        Log.d("DebugPipeline", "Triggered: starting transcription → summarization pipeline")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pending = summaryDao.getByStatus(
                    listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)
                )
                for (entity in pending) {
                    Log.d("DebugPipeline", "Processing session: ${entity.sessionId}")

                    val wavFile = File(entity.audioFilePath)
                    if (!wavFile.exists()) {
                        Log.w("DebugPipeline", "WAV file not found: ${entity.audioFilePath}")
                        continue
                    }

                    // Step 1: Transcription
                    Log.d("DebugPipeline", "Step 1: Transcribing via Groq...")
                    summaryDao.updateStatus(entity.sessionId, SummaryStatus.SUMMARIZING)
                    val transcriptionResult = transcriptionProvider.transcribe(wavFile)
                    if (transcriptionResult.isFailure) {
                        Log.e("DebugPipeline", "Transcription failed: ${transcriptionResult.exceptionOrNull()?.message}")
                        summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
                        continue
                    }
                    val transcriptionText = transcriptionResult.getOrThrow()
                    Log.d("DebugPipeline", "Step 1 done: transcription length=${transcriptionText.length}")

                    // Step 2: Summarization
                    Log.d("DebugPipeline", "Step 2: Summarizing via Gemini...")
                    val summaryResult = summaryProvider.summarize(transcriptionText)
                    if (summaryResult.isFailure) {
                        Log.e("DebugPipeline", "Summarization failed: ${summaryResult.exceptionOrNull()?.message}")
                        summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = summaryResult.exceptionOrNull()?.message)
                        continue
                    }
                    val output = summaryResult.getOrThrow()

                    summaryDao.updateStatusAndContent(
                        sessionId = entity.sessionId,
                        status = SummaryStatus.DONE,
                        title = output.title,
                        summaryText = output.summaryText,
                        transcriptionText = transcriptionText
                    )
                    Log.d("DebugPipeline", "Step 2 done: session ${entity.sessionId}")
                }
                Log.d("DebugPipeline", "Pipeline complete! ${pending.size} sessions processed")
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
