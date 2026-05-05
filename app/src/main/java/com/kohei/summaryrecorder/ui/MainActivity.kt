package com.kohei.summaryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kohei.summaryrecorder.audio.DebugConfig
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SessionHistory
import com.kohei.summaryrecorder.ui.theme.SummaryRecorderTheme
import com.kohei.summaryrecorder.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // E2E用: intent extra "debug" = true → DebugConfig有効化
        if (intent.getBooleanExtra("debug", false)) {
            DebugConfig.debugMode = true
        }

        // 権限チェック
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                1
            )
        }

        setContent {
            SummaryRecorderTheme {
                val viewModel: MainViewModel = hiltViewModel()
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabs = listOf("録音", "履歴")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("SummaryRecorder") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.onTabSelected(index) },
                        text = { Text(title) }
                    )
                }
            }

            when (uiState.selectedTab) {
                0 -> RecordingTab(viewModel = viewModel, uiState = uiState)
                1 -> HistoryTab(viewModel = viewModel, uiState = uiState)
            }
        }
    }
}

@Composable
fun RecordingTab(viewModel: MainViewModel, uiState: MainViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 録音ボタン
        Button(
            onClick = {
                if (uiState.isRecording) {
                    viewModel.stopRecording()
                } else {
                    viewModel.startRecording()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRecording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (uiState.isRecording) "録音停止" else "録音開始")
        }

        // #9: バックグラウンド録音継続中表示
        if (uiState.isRecording) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "🎤 録音中 — アプリを閉じてもバックグラウンドで継続します",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // チャンク状態一覧
        if (uiState.chunks.isNotEmpty()) {
            Text("チャンク状態", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(uiState.chunks) { chunk ->
                    ChunkRow(chunk)
                }
            }
        }

        // ローディング
        if (uiState.isLoading) {
            CircularProgressIndicator()
            Text("文字起こし完了待ち...")
        }

        // #7: 要約結果（コピー可能）
        uiState.summary?.let { summary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("要約結果", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    SelectionContainer {
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // #10: エラー表示 + リトライボタン
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.clearError()
                            viewModel.retryLastSummary()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("再試行")
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryTab(viewModel: MainViewModel, uiState: MainViewModel.UiState) {
    // タブ表示時に履歴を読込
    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.isSessionsLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("履歴がありません", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text("過去の録音 (${uiState.sessions.size}件)", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.sessions) { session ->
                    SessionCard(
                        session = session,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun SessionCard(session: SessionHistory, viewModel: MainViewModel) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = viewModel.formatDate(session.createdAt),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = viewModel.formatSessionStatus(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.failedChunks > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                // #8: ファイルサイズ表示
                Text(
                    text = "${session.totalChunks}チャンク",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_delete),
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("削除確認") },
            text = {
                Text("この録音データを削除しますか？\n日時: ${viewModel.formatDate(session.createdAt)}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(session.sessionId)
                        showDeleteDialog = false
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
fun ChunkRow(chunk: MainViewModel.ChunkUiItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("チャンク ${chunk.index}")
        StatusBadge(chunk.status)
    }
}

@Composable
fun StatusBadge(status: ChunkStatus) {
    val (text, color) = when (status) {
        ChunkStatus.PENDING -> "待機中" to MaterialTheme.colorScheme.outline
        ChunkStatus.UPLOADING -> "送信中" to MaterialTheme.colorScheme.primary
        ChunkStatus.DONE -> "完了" to MaterialTheme.colorScheme.primary
        ChunkStatus.FAILED -> "失敗" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
