package com.kohei.summaryrecorder.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.service.RecordingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    private val dao: ChunkDao,
    private val summaryRepo: SummaryRepository
) : ViewModel() {

    // ===== UI State =====

    data class UiState(
        val isRecording: Boolean = false,
        val sessionId: String = "",
        val chunks: List<ChunkUiItem> = emptyList(),
        val summary: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    data class ChunkUiItem(
        val index: Int,
        val status: ChunkStatus,
        val transcription: String?
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ===== 録音制御 =====

    fun startRecording(context: Context) {
        val sessionId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                isRecording = true,
                sessionId = sessionId,
                summary = null,
                error = null
            )
        }
        context.startService(
            RecordingService.startIntent(context, sessionId)
        )
        observeChunks(sessionId)
    }

    fun stopRecording(context: Context) {
        _uiState.update { it.copy(isRecording = false, isLoading = true) }
        context.startService(RecordingService.stopIntent(context))
    }

    // ===== チャンク監視 =====

    private fun observeChunks(sessionId: String) {
        // チャンク一覧表示用Flow
        viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks -> chunks.map { it.toUiItem() } }
                .onEach { items -> _uiState.update { it.copy(chunks = items) } }
                .collect {}
        }

        // 全DONE検知 → 要約トリガー
        viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks ->
                    chunks.isNotEmpty() && chunks.all { it.status == ChunkStatus.DONE }
                }
                .distinctUntilChanged()
                .filter { it }
                .onEach { summarizeAll(sessionId) }
                .collect {}
        }
    }

    private fun ChunkEntity.toUiItem() = ChunkUiItem(
        index = chunkIndex,
        status = status,
        transcription = transcriptionText
    )

    // ===== 要約 =====

    private suspend fun summarizeAll(sessionId: String) {
        val chunks = dao.getBySession(sessionId)
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }

        val result = summaryRepo.summarize(combinedText)

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    summary = result.getOrThrow(),
                    isLoading = false
                )
            }
            // DB物理削除（SUM-004）
            dao.deleteBySession(sessionId)
        } else {
            _uiState.update {
                it.copy(
                    error = "要約に失敗しました: ${result.exceptionOrNull()?.message}",
                    isLoading = false
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
