package com.kohei.summaryrecorder.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.ui.util.FormatUtil
import com.kohei.summaryrecorder.viewmodel.MainViewModel

@Composable
fun RecordingTabContent(viewModel: MainViewModel, uiState: MainViewModel.UiState) {
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val (freeGB, recordableHours) = storageInfo
    val isBlinking = uiState.isRecording && uiState.isPaused
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "blinkAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Info panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    uiState.isRecording && !uiState.isPaused -> MaterialTheme.colorScheme.primaryContainer
                    uiState.isRecording && uiState.isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = FormatUtil.formatTimer(uiState.recordingSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (uiState.isRecording) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface,
                    modifier = if (isBlinking) Modifier.graphicsLayer { alpha = blinkAlpha } else Modifier
                )
                val isActivelyRecording = uiState.isRecording && !uiState.isPaused
                LinearProgressIndicator(
                    progress = { if (isActivelyRecording) uiState.volumeLevel else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (isActivelyRecording) MaterialTheme.colorScheme.primary else Color.Gray,
                    trackColor = Color.LightGray,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.storage_free), style = MaterialTheme.typography.bodySmall)
                    Text("${freeGB} GB", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.storage_recordable), style = MaterialTheme.typography.bodySmall)
                    Text("${recordableHours} ${stringResource(R.string.unit_hours)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            OutlinedButton(
                onClick = { viewModel.stopRecording() },
                enabled = uiState.isRecording,
                modifier = Modifier.size(80.dp)
            ) {
                Text(stringResource(R.string.btn_stop))
            }

            Button(
                onClick = {
                    when {
                        !uiState.isRecording -> viewModel.startRecording()
                        uiState.isPaused -> viewModel.resumeRecording()
                        else -> viewModel.pauseRecording()
                    }
                },
                modifier = Modifier.size(80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(
                    when {
                        !uiState.isRecording -> stringResource(R.string.btn_record)
                        uiState.isPaused -> stringResource(R.string.btn_resume)
                        else -> stringResource(R.string.btn_pause)
                    }
                )
            }
        }

        // Background recording notice
        if (uiState.isRecording) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.recording_background),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Loading
        if (uiState.isLoading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.loading_transcription))
        }

        // Error
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.clearError() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.btn_close)) }
                }
            }
        }
    }
}
