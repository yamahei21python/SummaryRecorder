package com.kohei.summaryrecorder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.domain.controller.RecordingController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ChunkDao,
    private val summaryRepo: SummaryRepository,
    private val recordingController: RecordingController
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

    // observeChunks の Job を保持 → 再 startRecording 時に cancel
    private var observeJobs: List<Job> = emptyList()

    // ===== 録音制御 =====

    fun startRecording() {
        val sessionId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                isRecording = true,
                sessionId = sessionId,
                summary = null,
                error = null
            )
        }
        recordingController.startRecording(sessionId)
        observeChunks(sessionId)
    }

    fun stopRecording() {
        _uiState.update { it.copy(isRecording = false, isLoading = true) }
        recordingController.stopRecording()
    }

    // ===== チャンク監視 =====

    private fun observeChunks(sessionId: String) {
        // 古い購読をキャンセル（リーク防止）
        observeJobs.forEach { it.cancel() }

        // チャンク一覧表示用Flow
        val listJob = viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks -> chunks.map { it.toUiItem() } }
                .onEach { items -> _uiState.update { it.copy(chunks = items) } }
                .collect {}
        }

        // 全DONE検知 → 要約トリガー
        val summaryJob = viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks ->
                    chunks.isNotEmpty() && chunks.all { it.status == ChunkStatus.DONE }
                }
                .distinctUntilChanged()
                .filter { it }
                .onEach { summarizeAll(sessionId) }
                .collect {}
        }

        observeJobs = listOf(listJob, summaryJob)
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
