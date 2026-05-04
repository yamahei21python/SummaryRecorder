package com.kohei.summaryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.di.ServiceLocator
import com.kohei.summaryrecorder.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 権限チェック
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }

        val viewModel: MainViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(
                        dao = ServiceLocator.database.chunkDao(),
                        summaryRepo = ServiceLocator.summaryRepository
                    ) as T
                }
            }
        }

        setContent {
            MainScreen(viewModel = viewModel)
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    viewModel.stopRecording(context)
                } else {
                    viewModel.startRecording(context)
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
