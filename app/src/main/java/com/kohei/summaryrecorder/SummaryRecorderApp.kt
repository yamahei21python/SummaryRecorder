package com.kohei.summaryrecorder

import android.app.Application
import com.kohei.summaryrecorder.data.preferences.SettingsDataStore
import com.kohei.summaryrecorder.R
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SummaryRecorderApp : Application() {
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate() {
        super.onCreate()
        // 初回起動: デフォルト要約指示設定 + BuildConfig値をDataStoreに書き込む
        val defaultInstruction = getString(R.string.system_prompt_summary)
        settingsDataStore.setDefaultInstruction(defaultInstruction)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsDataStore.ensureDefaults(defaultInstruction)
        }
    }
}
