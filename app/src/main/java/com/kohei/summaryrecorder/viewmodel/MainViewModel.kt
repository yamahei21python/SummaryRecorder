package com.kohei.summaryrecorder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
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

    private fun observeChunks(sessionId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            chunkRepository.observeBySession(sessionId)
                .map { chunks ->
                    val items = chunks.map { it.toUiItem() }
                    val allDone = chunks.isNotEmpty() && chunks.all { it.status == ChunkStatus.DONE }
                    items to allDone
                }
                .distinctUntilChanged()
                .onEach { (items, _) ->
                    _uiState.update { it.copy(chunks = items) }
                }
                .also { flow ->
                    // 全チャンクDONEを検知した時のみ1回summarize。observeは継続。
                    launch {
                        flow.filter { (_, allDone) -> allDone }
                            .take(1)
                            .collect { summarizeAll(sessionId) }
                    }
                }
                .collect {}
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
