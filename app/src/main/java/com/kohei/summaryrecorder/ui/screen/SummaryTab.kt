package com.kohei.summaryrecorder.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.ui.util.FormatUtil
import com.kohei.summaryrecorder.viewmodel.MainViewModel

@Composable
fun SummaryTabContent(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState
) {
    var detailSessionId by remember { mutableStateOf<String?>(null) }
    var detailTab by remember { mutableIntStateOf(0) }

    if (detailSessionId != null) {
        val entity = uiState.summaries.find { it.sessionId == detailSessionId }
        if (entity != null) {
            SummaryDetailScreen(
                entity = entity,
                selectedTab = detailTab,
                onTabChange = { detailTab = it },
                onBack = { detailSessionId = null },
                viewModel = viewModel
            )
        } else {
            detailSessionId = null
        }
    } else {
        SummaryListScreen(
            viewModel = viewModel,
            uiState = uiState,
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
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.Article, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
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
        else -> FormatUtil.formatDate(entity.createdAt)
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
                text = FormatUtil.formatDate(entity.createdAt),
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
