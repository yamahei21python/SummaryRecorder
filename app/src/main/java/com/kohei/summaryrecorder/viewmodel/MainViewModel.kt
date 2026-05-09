package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.recorder.AudioConstants
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val transcriptionProvider: TranscriptionProvider,
    private val summaryProvider: SummaryProvider,
    private val recordingController: RecordingController,
    private val summaryDao: SummaryDao,
    private val deleteSummaryUseCase: DeleteSummaryUseCase,
    private val application: Application,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_IS_PAUSED = "is_paused"
        private const val KEY_RECORDING_SECONDS = "recording_seconds"
        private const val KEY_RECORDING_START_TIME = "recording_start_time"
        private const val RECORDINGS_DIR = "recordings"
    }

    data class UiState(
        val selectedTab: Int = 0,
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val sessionId: String = "",
        val recordingSeconds: Int = 0,
        val summary: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val summaries: List<SummaryEntity> = emptyList(),
        val unreadBadgeCount: Int = 0,
        val volumeLevel: Float = 0f
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _storageInfo = MutableStateFlow<Pair<Long, Long>>(0L to 0L)
    val storageInfo: StateFlow<Pair<Long, Long>> = _storageInfo.asStateFlow()

    private var timerJob: Job? = null

    private var persistedSessionId: String?
        get() = savedStateHandle[KEY_SESSION_ID]
        set(value) { savedStateHandle[KEY_SESSION_ID] = value }

    private var persistedIsRecording: Boolean
        get() = savedStateHandle[KEY_IS_RECORDING] ?: false
        set(value) { savedStateHandle[KEY_IS_RECORDING] = value }

    private var persistedIsPaused: Boolean
        get() = savedStateHandle[KEY_IS_PAUSED] ?: false
        set(value) { savedStateHandle[KEY_IS_PAUSED] = value }

    private var persistedRecordingSeconds: Int
        get() = savedStateHandle[KEY_RECORDING_SECONDS] ?: 0
        set(value) { savedStateHandle[KEY_RECORDING_SECONDS] = value }

    private var persistedRecordingStartTime: Long
        get() = savedStateHandle[KEY_RECORDING_START_TIME] ?: 0L
        set(value) { savedStateHandle[KEY_RECORDING_START_TIME] = value }

    init {
        observeSummaries()
        restorePreviousSession()
        retryAndCleanup()
        startStoragePolling()
        startVolumeMonitoring()
    }

    private fun observeSummaries() {
        viewModelScope.launch {
            summaryDao.observeAll().collect { list ->
                val badgeCount = list.count { it.status == SummaryStatus.DONE && !it.isRead }
                val loading = list.any { it.status == SummaryStatus.RECORDED || it.status == SummaryStatus.SUMMARIZING }
                _uiState.update { it.copy(summaries = list, unreadBadgeCount = badgeCount, isLoading = loading) }
            }
        }
    }

    private fun restorePreviousSession() {
        val prevSession = persistedSessionId
        if (prevSession != null && persistedIsRecording) {
            val startTime = persistedRecordingStartTime
            val elapsedSeconds = if (startTime > 0 && !persistedIsPaused) {
                ((System.currentTimeMillis() - startTime) / 1000).toInt()
            } else {
                persistedRecordingSeconds
            }
            persistedRecordingSeconds = elapsedSeconds

            _uiState.update {
                it.copy(
                    isRecording = true,
                    isPaused = persistedIsPaused,
                    sessionId = prevSession,
                    recordingSeconds = elapsedSeconds
                )
            }
            if (!persistedIsPaused) {
                startTimer()
            }
        }
    }

    private fun retryAndCleanup() {
        viewModelScope.launch {
            recordingController.awaitReady()
            retryPendingRecords()
            cleanupOrphanFiles(recordingController.currentSessionId)
        }
    }

    private fun startStoragePolling() {
        viewModelScope.launch {
            while (true) {
                try { updateStorageInfo() } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private fun startVolumeMonitoring() {
        viewModelScope.launch {
            while (true) {
                try {
                    if (_uiState.value.isRecording && !_uiState.value.isPaused) {
                        val level = recordingController.currentVolumeLevel
                        _uiState.update { it.copy(volumeLevel = level) }
                    } else {
                        _uiState.update { it.copy(volumeLevel = 0f) }
                    }
                } catch (_: Exception) {}
                delay(100)
            }
        }
    }

    // ===== Tab selection =====

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    // ===== Recording controls =====

    fun startRecording() {
        val sessionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        persistedSessionId = sessionId
        persistedIsRecording = true
        persistedIsPaused = false
        persistedRecordingSeconds = 0
        persistedRecordingStartTime = startTime

        _uiState.update {
            it.copy(
                isRecording = true,
                isPaused = false,
                sessionId = sessionId,
                recordingSeconds = 0,
                summary = null,
                error = null,
                isLoading = false
            )
        }
        recordingController.startRecording(sessionId)
        startTimer()
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return

        persistedIsRecording = false
        persistedIsPaused = false
        stopTimer()
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                recordingSeconds = 0,
                isLoading = true
            )
        }
        recordingController.stopRecording()
    }

    fun pauseRecording() {
        if (!_uiState.value.isRecording || _uiState.value.isPaused) return
        persistedIsPaused = true
        _uiState.update { it.copy(isPaused = true) }
        recordingController.pauseRecording()
        stopTimer()
    }

    fun resumeRecording() {
        if (!_uiState.value.isRecording || !_uiState.value.isPaused) return
        persistedIsPaused = false
        _uiState.update { it.copy(isPaused = false) }
        recordingController.resumeRecording()
        startTimer()
    }

    // ===== Timer =====

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _uiState.value.recordingSeconds
                persistedRecordingSeconds = current + 1
                _uiState.update { it.copy(recordingSeconds = current + 1) }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ===== Retry pending records (app restart) =====

    private suspend fun retryPendingRecords() {
        val pending = summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING))
        for (entity in pending) {
            retrySession(entity)
        }
    }

    // ===== Orphan file cleanup =====

    private suspend fun cleanupOrphanFiles(excludeSessionId: String?) {
        val recordingsDir = File(application.filesDir, RECORDINGS_DIR)
        if (!recordingsDir.exists()) return

        val knownSessionIds = summaryDao.getAll().map { it.sessionId }.toMutableSet()
        if (excludeSessionId != null) knownSessionIds.add(excludeSessionId)

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            recordingsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".wav")) {
                    val sessionId = file.nameWithoutExtension
                    if (sessionId !in knownSessionIds) {
                        file.delete()
                    }
                } else if (file.isDirectory) {
                    val sessionId = file.name
                    if (sessionId !in knownSessionIds) {
                        file.deleteRecursively()
                    }
                }
            }
        }
    }

    // ===== Summary operations =====

    fun deleteSummary(sessionId: String, audioFilePath: String) {
        viewModelScope.launch {
            deleteSummaryUseCase.execute(sessionId, audioFilePath)
        }
    }

    fun retrySummary(sessionId: String) {
        viewModelScope.launch {
            val entity = summaryDao.getBySessionId(sessionId) ?: return@launch
            retrySession(entity)
        }
    }

    private suspend fun retrySession(entity: SummaryEntity) {
        summaryDao.updateStatus(entity.sessionId, SummaryStatus.SUMMARIZING)
        try {
            val wavFile = File(entity.audioFilePath)
            if (!wavFile.exists()) {
                summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = "音声ファイルが見つかりません")
                return
            }

            val transcriptionResult = transcriptionProvider.transcribe(wavFile)
            if (transcriptionResult.isFailure) {
                summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
                return
            }
            val transcriptionText = transcriptionResult.getOrThrow()

            val summaryResult = summaryProvider.summarize(transcriptionText)
            if (summaryResult.isFailure) {
                summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = summaryResult.exceptionOrNull()?.message)
                return
            }
            val output = summaryResult.getOrThrow()

            summaryDao.updateStatusAndContent(
                sessionId = entity.sessionId,
                status = SummaryStatus.DONE,
                title = output.title,
                summaryText = output.summaryText,
                transcriptionText = transcriptionText
            )
        } catch (e: Exception) {
            try {
                summaryDao.updateStatus(entity.sessionId, SummaryStatus.ERROR, errorMessage = e.message)
            } catch (_: Exception) {}
        }
    }

    fun markAsRead(sessionId: String) {
        viewModelScope.launch {
            summaryDao.updateRead(sessionId, true)
        }
    }

    fun updateTitle(sessionId: String, title: String) {
        viewModelScope.launch {
            summaryDao.updateTitle(sessionId, title)
        }
    }

    // ===== Storage info =====

    private fun updateStorageInfo() {
        val stat = StatFs(application.filesDir.absolutePath)
        val freeBytes = stat.availableBytes
        val bytesPerSecond = AudioConstants.BYTES_PER_SECOND
        val recordableHours = freeBytes / bytesPerSecond / 3600
        _storageInfo.value = Pair(freeBytes / (1024 * 1024 * 1024), recordableHours)
    }

    // ===== Utilities =====

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
