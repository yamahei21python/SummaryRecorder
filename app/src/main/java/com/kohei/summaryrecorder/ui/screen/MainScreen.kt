package com.kohei.summaryrecorder.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import com.kohei.summaryrecorder.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    settingsDataStore: SettingsDataStore
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }

    // ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // Pause on tab switch
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
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.testTag("btn_settings")
                        ) {
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
                            Icon(Icons.AutoMirrored.Filled.Article, stringResource(R.string.cd_summary))
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
                2 -> SummaryTabContent(viewModel = viewModel, uiState = uiState)
            }
        }
    }

    // Settings dialog
    if (showSettingsDialog) {
        SettingsScreen(
            onDismiss = { showSettingsDialog = false },
            dataStore = settingsDataStore
        )
    }
}
