package com.kohei.summaryrecorder

import android.app.Application
import com.kohei.summaryrecorder.di.ServiceLocator

class SummaryRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)

        // local.properties → BuildConfig経由でAPIキー読込
        val groqKey = BuildConfig.GROQ_API_KEY
        val geminiKey = BuildConfig.GEMINI_API_KEY
        ServiceLocator.setApiKeys(groqKey, geminiKey)
    }
}
