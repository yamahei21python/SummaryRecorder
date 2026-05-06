package com.kohei.summaryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.ui.theme.SummaryRecorderTheme
import com.kohei.summaryrecorder.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private val PLAYBACK_SPEED_KEY = floatPreferencesKey("playback_speed")
private val SPEED_CYCLE = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f, 0.5f, 0.8f)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var dataStore: DataStore<Preferences>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra("debug", false)) {
            DebugConfig.debugMode = true
        }

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }

        setContent {
            SummaryRecorderTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val speed by dataStore.data.map { it[PLAYBACK_SPEED_KEY] ?: 1.0f }
                    .collectAsStateWithLifecycle(initialValue = 1.0f)
                val scope = rememberCoroutineScope()
                MainScreen(viewModel = viewModel, playbackSpeed = speed, onSpeedChange = { newSpeed ->
                    scope.launch { dataStore.edit { it[PLAYBACK_SPEED_KEY] = newSpeed } }
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, playbackSpeed: Float, onSpeedChange: (Float) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    // ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // タブ切り替え時にExoPlayer一時停止
    LaunchedEffect(uiState.selectedTab) {
        if (uiState.selectedTab != 1 && exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (uiState.selectedTab) {
                        0 -> stringResource(R.string.tab_title_recording)
                        1 -> stringResource(R.string.tab_title_audio)
                        2 -> stringResource(R.string.tab_title_summary)
                        else -> stringResource(R.string.tab_title_recording)
                    })
                },
                actions = {
                    if (uiState.selectedTab == 0) {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Text(stringResource(R.string.btn_settings))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.onTabSelected(0) },
                    icon = { Icon(Icons.Default.Mic, stringResource(R.string.cd_recording)) },
                    label = { Text(stringResource(R.string.tab_label_recording)) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.onTabSelected(1) },
                    icon = { Icon(Icons.Default.PlayCircle, stringResource(R.string.cd_audio)) },
                    label = { Text(stringResource(R.string.tab_label_audio)) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.onTabSelected(2) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.unreadBadgeCount > 0) {
                                    Badge {
                                        Text(if (uiState.unreadBadgeCount > 99) stringResource(R.string.badge_overflow) else uiState.unreadBadgeCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Article, stringResource(R.string.cd_summary))
                        }
                    },
                    label = { Text(stringResource(R.string.tab_label_summary)) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.selectedTab) {
                0 -> RecordingTabContent(viewModel = viewModel, uiState = uiState)
                1 -> AudioTabContent(
                    viewModel = viewModel,
                    uiState = uiState,
                    exoPlayer = exoPlayer,
                    playbackSpeed = playbackSpeed,
                    onSpeedChange = onSpeedChange
                )
                2 -> SummaryTabContent(viewModel = viewModel, uiState = uiState, exoPlayer = exoPlayer)
            }
        }
    }

    // Settings dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(stringResource(R.string.dialog_settings_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            exportLauncher.launch("summaryrecorder_backup_${System.currentTimeMillis()}.zip")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_export)) }
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            showImportConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_import)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // Import confirmation dialog
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.btn_import)) },
            text = { Text(stringResource(R.string.import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text(stringResource(R.string.btn_import)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

// ==================== TAB 1: 録音操作 ====================

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
                // Timer
                Text(
                    text = viewModel.formatTimer(uiState.recordingSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (uiState.isRecording) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface,
                    modifier = if (isBlinking) Modifier.graphicsLayer { alpha = blinkAlpha } else Modifier
                )
                // Volume bar
                val isActivelyRecording = uiState.isRecording && !uiState.isPaused
                LinearProgressIndicator(
                    progress = { if (isActivelyRecording) uiState.volumeLevel else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (isActivelyRecording) MaterialTheme.colorScheme.primary else Color.Gray,
                    trackColor = Color.LightGray,
                )
                // Storage info
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
            // Stop button
            OutlinedButton(
                onClick = { viewModel.stopRecording() },
                enabled = uiState.isRecording,
                modifier = Modifier.size(80.dp)
            ) {
                Text(stringResource(R.string.btn_stop))
            }

            // Record / Pause / Resume button
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

// ==================== TAB 2: 音声確認 ====================

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
                                    // W3: 空パスガード
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
                            Text(viewModel.formatDuration(positionMs))
                            Text(viewModel.formatDuration(durationMs))
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
        else -> viewModel.formatDate(entity.createdAt)
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
                    Text(viewModel.formatDate(entity.createdAt), style = MaterialTheme.typography.bodySmall)
                    Text(viewModel.formatDuration(entity.durationMs), style = MaterialTheme.typography.bodySmall)
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

// ==================== TAB 3: 要約・読みおこし ====================

@Composable
fun SummaryTabContent(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState,
    exoPlayer: ExoPlayer
) {
    var detailSessionId by remember { mutableStateOf<String?>(null) }
    var detailTab by remember { mutableIntStateOf(0) } // 0=要約, 1=読みおこし

    if (detailSessionId != null) {
        val entity = uiState.summaries.find { it.sessionId == detailSessionId }
        if (entity != null) {
            SummaryDetailScreen(
                entity = entity,
                selectedTab = detailTab,
                onTabChange = { detailTab = it },
                onBack = { detailSessionId = null },
                viewModel = viewModel,
                exoPlayer = exoPlayer
            )
        } else {
            detailSessionId = null
        }
    } else {
        SummaryListScreen(
            viewModel = viewModel,
            uiState = uiState,
            exoPlayer = exoPlayer,
            onSelect = { sessionId ->
                detailSessionId = sessionId
                viewModel.markAsRead(sessionId)
            }
        )
    }
}

@Composable
fun SummaryListScreen(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState,
    exoPlayer: ExoPlayer,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Article, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.empty_no_recordings))
                    Text(stringResource(R.string.empty_start_recording), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.summaries) { entity ->
                    SummaryCard(
                        entity = entity,
                        viewModel = viewModel,
                        onClick = { onSelect(entity.sessionId) },
                        onRetry = { viewModel.retrySummary(entity.sessionId) },
                        onDelete = {
                            // ExoPlayerは触らない。再生中アイテムの削除はAudioTab側で制御。
                            // 削除後に再生しようとした場合はExoPlayerがエラーを出すためそちらで対応。
                            viewModel.deleteSummary(entity.sessionId, entity.audioFilePath)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryCard(
    entity: SummaryEntity,
    viewModel: MainViewModel,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isProcessing = entity.status == SummaryStatus.RECORDED || entity.status == SummaryStatus.SUMMARIZING
    val displayTitle = when {
        entity.title.isNotBlank() -> entity.title
        isProcessing -> stringResource(R.string.title_placeholder_summarizing)
        else -> viewModel.formatDate(entity.createdAt)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("summary_card_${entity.sessionId}"),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showMenu = true }) { Text("⋯") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.btn_delete)) }, onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        })
                    }
                }
            }
            if (!isProcessing) {
                Text(
                    text = entity.summaryText.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = viewModel.formatDate(entity.createdAt),
                style = MaterialTheme.typography.labelSmall
            )
            when (entity.status) {
                SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                SummaryStatus.ERROR -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.error_label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        entity.errorMessage?.let { Text(it.take(50), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.btn_retry)) }
                    }
                }
                else -> {}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryDetailScreen(
    entity: SummaryEntity,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel,
    exoPlayer: ExoPlayer
) {
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(entity.sessionId) {
        viewModel.markAsRead(entity.sessionId)
    }

    val isProcessing = entity.status == SummaryStatus.RECORDED || entity.status == SummaryStatus.SUMMARIZING
    val displayTitle = when {
        entity.title.isNotBlank() -> entity.title
        isProcessing -> stringResource(R.string.title_placeholder_summarizing)
        else -> viewModel.formatDate(entity.createdAt)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("←") }
            },
            actions = {
                TextButton(onClick = { showEditDialog = true }) { Text(stringResource(R.string.btn_edit)) }
            }
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { onTabChange(0) }, text = { Text(stringResource(R.string.tab_summary)) })
            Tab(selected = selectedTab == 1, onClick = { onTabChange(1) }, text = { Text(stringResource(R.string.tab_transcription)) })
        }

        val text = if (selectedTab == 0) entity.summaryText else entity.transcriptionText
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.padding(16.dp).fillMaxSize(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (showEditDialog) {
        var editTitle by remember { mutableStateOf(entity.title) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.dialog_edit_title)) },
            text = {
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text(stringResource(R.string.label_title)) },
                    supportingText = { Text("${editTitle.length}/20") },
                    isError = editTitle.length > 20
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editTitle.length <= 20) {
                            viewModel.updateTitle(entity.sessionId, editTitle)
                            showEditDialog = false
                        }
                    },
                    enabled = editTitle.length <= 20
                ) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}
