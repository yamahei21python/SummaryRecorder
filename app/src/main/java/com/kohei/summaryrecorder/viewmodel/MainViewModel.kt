package com.kohei.summaryrecorder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summarizeUseCase: SummarizeUseCase,
    private val recordingController: RecordingController
) : ViewModel() {

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

    private var observeJob: Job? = null
    private var summarized = false

    fun startRecording() {
        // 二重起動防止
        stopRecording()
        
        val sessionId = UUID.randomUUID().toString()
        summarized = false
        
        _uiState.update {
            it.copy(
                isRecording = true,
                sessionId = sessionId,
                chunks = emptyList(),
                summary = null,
                error = null,
                isLoading = false
            )
        }
        recordingController.startRecording(sessionId)
        observeChunks(sessionId)
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return

        _uiState.update { it.copy(isRecording = false, isLoading = true) }
        recordingController.stopRecording()

        if (_uiState.value.chunks.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun observeChunks(sessionId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            chunkRepository.getChunksFlow(sessionId)
                .map { chunks ->
                    val items = chunks.map { it.toUiItem() }
                    // すべてのチャンクが最終状態（DONE or FAILED）になったかを確認
                    val allTerminal = chunks.isNotEmpty() && chunks.all { 
                        it.status == ChunkStatus.DONE || it.status == ChunkStatus.FAILED 
                    }
                    items to allTerminal
                }
                .distinctUntilChanged()
                .collect { (items, allTerminal) ->
                    _uiState.update { it.copy(chunks = items) }
                    
                    val isRecording = _uiState.value.isRecording

                    if (!isRecording && (allTerminal || items.isEmpty())) {
                        _uiState.update { it.copy(isLoading = false) }
                    }

                    if (!isRecording && allTerminal && !summarized) {
                        summarized = true
                        summarizeAll(sessionId)
                    }
                }
        }
    }

    private fun ChunkEntity.toUiItem() = ChunkUiItem(
        index = chunkIndex,
        status = status,
        transcription = transcriptionText
    )

    private suspend fun summarizeAll(sessionId: String) {
        val result = summarizeUseCase.execute(sessionId)

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    summary = result.getOrThrow(),
                    isLoading = false
                )
            }
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
