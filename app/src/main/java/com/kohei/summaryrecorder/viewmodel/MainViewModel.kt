package com.kohei.summaryrecorder.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SessionHistory
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summarizeUseCase: SummarizeUseCase,
    private val recordingController: RecordingController,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_SUMMARIZED = "summarized"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_IS_RECORDING = "is_recording"
    }

    data class UiState(
        val selectedTab: Int = 0,
        val isRecording: Boolean = false,
        val sessionId: String = "",
        val chunks: List<ChunkUiItem> = emptyList(),
        val summary: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val sessions: List<SessionHistory> = emptyList(),
        val isSessionsLoading: Boolean = false
    )

    data class ChunkUiItem(
        val index: Int,
        val status: ChunkStatus,
        val transcription: String?
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private var summarized: Boolean
        get() = savedStateHandle[KEY_SUMMARIZED] ?: false
        set(value) { savedStateHandle[KEY_SUMMARIZED] = value }

    // #9: プロセス死復帰 — SavedStateHandleからセッション復元
    private var persistedSessionId: String?
        get() = savedStateHandle[KEY_SESSION_ID]
        set(value) { savedStateHandle[KEY_SESSION_ID] = value }

    private var persistedIsRecording: Boolean
        get() = savedStateHandle[KEY_IS_RECORDING] ?: false
        set(value) { savedStateHandle[KEY_IS_RECORDING] = value }

    init {
        // #9: プロセス再生成時に前回のセッション観測を再開
        val prevSession = persistedSessionId
        if (prevSession != null && !summarized) {
            observeChunks(prevSession)
        }
    }

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
        if (index == 1) {
            loadSessions()
        }
    }

    fun startRecording() {
        // 二重起動防止
        stopRecording()
        
        val sessionId = UUID.randomUUID().toString()
        summarized = false
        persistedSessionId = sessionId
        persistedIsRecording = true
        
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

        val hasChunks = _uiState.value.chunks.isNotEmpty()
        persistedIsRecording = false
        _uiState.update { it.copy(isRecording = false, isLoading = hasChunks) }
        recordingController.stopRecording()
    }

    private fun observeChunks(sessionId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            chunkRepository.getChunksFlow(sessionId)
                .collect { chunks ->
                    val items = chunks.map { it.toUiItem() }
                    val hasLast = chunks.any { it.isLast }
                    val allTerminal = chunks.isNotEmpty() && chunks.all {
                        it.status == ChunkStatus.DONE || it.status == ChunkStatus.FAILED
                    }

                    _uiState.update { it.copy(chunks = items) }

                    val recording = _uiState.value.isRecording
                    if (!recording && (allTerminal || items.isEmpty())) {
                        _uiState.update { it.copy(isLoading = false) }
                        // #10: セッション終了後にobserveJobをキャンセル（リソースリーク防止）
                        if (allTerminal) {
                            observeJob?.cancel()
                        }
                    }

                    if (!recording && hasLast && chunks.all { it.status == ChunkStatus.DONE } && !summarized) {
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
            // #2: 要約成功後にセッションデータを自動削除
            try {
                chunkRepository.deleteSessionData(sessionId)
            } catch (e: Exception) {
                // 削除失敗は致命的ではない。ログだけ残す
                android.util.Log.w("MainViewModel", "Auto-cleanup failed for session $sessionId", e)
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

    // ===== 手動削除機能 =====

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSessionsLoading = true) }
            try {
                val sessions = chunkRepository.getSessionHistory()
                _uiState.update { it.copy(sessions = sessions, isSessionsLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSessionsLoading = false) }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chunkRepository.deleteSessionData(sessionId)
                // 一覧を再読込
                val sessions = chunkRepository.getSessionHistory()
                _uiState.update { it.copy(sessions = sessions) }
            } catch (e: Exception) {
                // 何もしない
            }
        }
    }

    fun retryLastSummary() {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return

        _uiState.update { it.copy(error = null, isLoading = true) }
        summarized = false
        viewModelScope.launch {
            summarizeAll(sessionId)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ユーティリティ: タイムスタンプ表示用
    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatSessionStatus(session: SessionHistory): String {
        return if (session.failedChunks > 0) {
            "${session.doneChunks}/${session.totalChunks} (${session.failedChunks}失敗)"
        } else {
            "${session.doneChunks}/${session.totalChunks} 完了"
        }
    }
}
