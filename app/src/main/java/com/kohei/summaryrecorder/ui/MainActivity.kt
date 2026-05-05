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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kohei.summaryrecorder.data.db.ChunkStatus
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

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // タイトル
        Text(
            text = "SummaryRecorder",
            style = MaterialTheme.typography.headlineMedium
        )

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

        // 要約結果
        uiState.summary?.let { summary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("要約結果", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // エラー表示
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
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
        ChunkStatus.PENDING -> "待機中" to Color.Gray
        ChunkStatus.UPLOADING -> "送信中" to Color(0xFF2196F3)
        ChunkStatus.DONE -> "完了" to Color(0xFF4CAF50)
        ChunkStatus.FAILED -> "失敗" to Color(0xFFF44336)
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
