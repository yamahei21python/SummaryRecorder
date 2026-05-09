package com.kohei.summaryrecorder.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.ui.util.FormatUtil
import com.kohei.summaryrecorder.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryDetailScreen(
    entity: SummaryEntity,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel
) {
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(entity.sessionId) {
        viewModel.markAsRead(entity.sessionId)
    }

    val isProcessing = entity.status == SummaryStatus.RECORDED || entity.status == SummaryStatus.SUMMARIZING
    val displayTitle = when {
        entity.title.isNotBlank() -> entity.title
        isProcessing -> stringResource(R.string.title_placeholder_summarizing)
        else -> FormatUtil.formatDate(entity.createdAt)
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
                    isError = editTitle.length > 20,
                    modifier = Modifier.semantics { testTag = "edit_title_field" }
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
