package com.kohei.summaryrecorder.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    dataStore: SettingsDataStore
) {
    val scope = rememberCoroutineScope()

    val groqKey by dataStore.groqApiKey.collectAsState(initial = "")
    val geminiKey by dataStore.geminiApiKey.collectAsState(initial = "")
    val instruction by dataStore.summaryInstruction.collectAsState(initial = "")

    var groqKeyVisible by remember { mutableStateOf(false) }
    var geminiKeyVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("設定") },
                        navigationIcon = {
                            TextButton(onClick = onDismiss) { Text("閉じる") }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ---- 文字起こし ----
                    SettingsGroup(title = "文字起こし") {
                        ApiKeyField(
                            label = "Groq API Key",
                            value = groqKey,
                            visible = groqKeyVisible,
                            onToggleVisibility = { groqKeyVisible = !groqKeyVisible },
                            onValueChange = { scope.launch { dataStore.updateGroqApiKey(it) } }
                        )
                    }

                    // ---- 要約 ----
                    SettingsGroup(title = "要約") {
                        ApiKeyField(
                            label = "Gemini API Key",
                            value = geminiKey,
                            visible = geminiKeyVisible,
                            onToggleVisibility = { geminiKeyVisible = !geminiKeyVisible },
                            onValueChange = { scope.launch { dataStore.updateGeminiApiKey(it) } }
                        )
                    }

                    // ---- 要約指示 ----
                    SettingsGroup(title = "要約指示") {
                        OutlinedTextField(
                            value = instruction,
                            onValueChange = { scope.launch { dataStore.updateSummaryInstruction(it) } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("要約の指示") },
                            minLines = 2,
                            maxLines = 5
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    dataStore.updateSummaryInstruction("")
                                }
                            }
                        ) {
                            Text("デフォルトに戻す")
                        }
                    }
                }
            }
        }
    }
}

// ---- ヘルパーコンポーザブル ----

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "非表示" else "表示"
                )
            }
        }
    )
}
