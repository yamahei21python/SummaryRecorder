package com.kohei.summaryrecorder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ChunkDao,
    private val summarizeUseCase: SummarizeUseCase,
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

        val job = viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks ->
                    val items = chunks.map { it.toUiItem() }
                    val allDone = chunks.isNotEmpty() && chunks.all { it.status == ChunkStatus.DONE }
                    Triple(items, allDone, chunks)
                }
                .distinctUntilChanged()
                .collect { (items, allDone, _) ->
                    _uiState.update { it.copy(chunks = items) }
                    if (allDone) {
                        summarizeAll(sessionId)
                    }
                }
        }
        observeJobs = listOf(job)
    }

    private fun ChunkEntity.toUiItem() = ChunkUiItem(
        index = chunkIndex,
        status = status,
        transcription = transcriptionText
    )

    // ===== 要約 =====

    private suspend fun summarizeAll(sessionId: String) {
        val result = summarizeUseCase.execute(sessionId)

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    summary = result.getOrThrow(),
                    isLoading = false
                )
            }
            // dao.deleteBySession() は UseCase内に移動済み
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
