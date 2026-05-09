package com.kohei.summaryrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import com.kohei.summaryrecorder.ui.screen.MainScreen
import com.kohei.summaryrecorder.ui.theme.SummaryRecorderTheme
import com.kohei.summaryrecorder.ui.util.PLAYBACK_SPEED_KEY
import com.kohei.summaryrecorder.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                MainScreen(
                    viewModel = viewModel,
                    playbackSpeed = speed,
                    onSpeedChange = { newSpeed ->
                        scope.launch { dataStore.edit { it[PLAYBACK_SPEED_KEY] = newSpeed } }
                    },
                    settingsDataStore = settingsDataStore
                )
            }
        }
    }
}
