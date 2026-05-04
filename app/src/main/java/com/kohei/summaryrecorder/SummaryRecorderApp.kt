package com.kohei.summaryrecorder

import android.app.Application
import com.kohei.summaryrecorder.di.ServiceLocator
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SummaryRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)

        val groqKey = BuildConfig.GROQ_API_KEY
        val geminiKey = BuildConfig.GEMINI_API_KEY
        ServiceLocator.setApiKeys(groqKey, geminiKey)
    }
}
