package com.kohei.summaryrecorder.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.ui.util.FormatUtil
import com.kohei.summaryrecorder.ui.util.SPEED_CYCLE
import com.kohei.summaryrecorder.viewmodel.MainViewModel

@Suppress("DEPRECATION")
@Composable
fun AudioTabContent(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState,
    exoPlayer: ExoPlayer,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit
) {
    var currentPlayingId by remember { mutableStateOf<String?>(null) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isCurrentlyPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }

    // Playback state listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isCurrentlyPlaying = isPlaying
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    exoPlayer.pause()
                    exoPlayer.seekTo(0)
                    isCurrentlyPlaying = false
                    positionMs = 0L
                    sliderPosition = 0f
                }
                durationMs = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Update position periodically
    LaunchedEffect(isCurrentlyPlaying) {
        while (isCurrentlyPlaying) {
            positionMs = exoPlayer.currentPosition
            durationMs = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
            sliderPosition = if (durationMs > 0) exoPlayer.currentPosition.toFloat() / durationMs else 0f
            kotlinx.coroutines.delay(200)
        }
    }

    // Lifecycle pause
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && isCurrentlyPlaying) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.empty_no_recordings))
                    Text(stringResource(R.string.empty_start_recording), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.summaries) { entity ->
                    AudioCard(
                        entity = entity,
                        isPlaying = currentPlayingId == entity.sessionId && isCurrentlyPlaying,
                        viewModel = viewModel,
                        onPlay = {
                            if (currentPlayingId == entity.sessionId && isCurrentlyPlaying) {
                                exoPlayer.pause()
                                isCurrentlyPlaying = false
                            } else {
                                if (currentPlayingId != entity.sessionId) {
                                    if (entity.audioFilePath.isBlank()) return@AudioCard
                                    val audioFile = java.io.File(entity.audioFilePath)
                                    if (!audioFile.exists()) return@AudioCard
                                    val mediaItem = androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(audioFile))
                                    exoPlayer.setMediaItem(mediaItem)
                                    exoPlayer.prepare()
                                    currentPlayingId = entity.sessionId
                                }
                                exoPlayer.setPlaybackSpeed(playbackSpeed)
                                exoPlayer.play()
                            }
                        },
                        onDelete = {
                            if (currentPlayingId == entity.sessionId) {
                                exoPlayer.stop()
                                exoPlayer.clearMediaItems()
                                currentPlayingId = null
                                isCurrentlyPlaying = false
                            }
                            viewModel.deleteSummary(entity.sessionId, entity.audioFilePath)
                        }
                    )
                }
            }

            // Playback controls
            if (currentPlayingId != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Slider(
                            value = sliderPosition,
                            onValueChange = {
                                sliderPosition = it
                                if (durationMs > 0) exoPlayer.seekTo((it * durationMs).toLong())
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(FormatUtil.formatDuration(positionMs))
                            Text(FormatUtil.formatDuration(durationMs))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition - 60000) }) { Text(stringResource(R.string.seek_back_60)) }
                            TextButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition - 10000) }) { Text(stringResource(R.string.seek_back_10)) }
                            IconButton(onClick = {
                                if (isCurrentlyPlaying) exoPlayer.pause() else {
                                    exoPlayer.setPlaybackSpeed(playbackSpeed)
                                    exoPlayer.play()
                                }
                            }) {
                                Icon(
                                    if (isCurrentlyPlaying) Icons.Default.Pause
                                    else Icons.Default.PlayArrow,
                                    if (isCurrentlyPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            TextButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10000) }) { Text(stringResource(R.string.seek_forward_10)) }
                            TextButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 60000) }) { Text(stringResource(R.string.seek_forward_60)) }
                            TextButton(onClick = {
                                val idx = SPEED_CYCLE.indexOfFirst { it == playbackSpeed }
                                val next = SPEED_CYCLE[(idx + 1) % SPEED_CYCLE.size]
                                onSpeedChange(next)
                                exoPlayer.setPlaybackSpeed(next)
                            }) { Text("${playbackSpeed}x") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioCard(
    entity: SummaryEntity,
    isPlaying: Boolean,
    viewModel: MainViewModel,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isProcessing = entity.status == SummaryStatus.RECORDED || entity.status == SummaryStatus.SUMMARIZING
    val displayTitle = when {
        entity.title.isNotBlank() -> entity.title
        isProcessing -> stringResource(R.string.title_placeholder_summarizing)
        else -> FormatUtil.formatDate(entity.createdAt)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onPlay
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(FormatUtil.formatDate(entity.createdAt), style = MaterialTheme.typography.bodySmall)
                    Text(FormatUtil.formatDuration(entity.durationMs), style = MaterialTheme.typography.bodySmall)
                }
                if (entity.status == SummaryStatus.RECORDED || entity.status == SummaryStatus.SUMMARIZING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Text("⋯")
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}
