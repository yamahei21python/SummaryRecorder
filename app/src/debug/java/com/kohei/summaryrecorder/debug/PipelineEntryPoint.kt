package com.kohei.summaryrecorder.debug

import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.service.TranscriptionUploader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only Hilt EntryPoint.
 * BroadcastReceiverは@AndroidEntryPointを使えないため、
 * EntryPointAccessors経由でDI済みオブジェクトを取得する。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PipelineEntryPoint {
    fun transcriptionUploader(): TranscriptionUploader
    fun summarizeUseCase(): SummarizeUseCase
    fun summaryDao(): SummaryDao
}
