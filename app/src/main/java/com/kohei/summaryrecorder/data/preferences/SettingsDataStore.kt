package com.kohei.summaryrecorder.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kohei.summaryrecorder.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        private val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val SUMMARY_INSTRUCTION = stringPreferencesKey("summary_instruction")
        private val INITIALIZED = booleanPreferencesKey("initialized")
    }

    /** デフォルト要約指示（Applicationから設定） */
    var defaultInstruction: String = ""
        private set

    fun setDefaultInstruction(value: String) { defaultInstruction = value }

    val groqApiKey: Flow<String> = dataStore.data.map { it[GROQ_API_KEY] ?: "" }
    val geminiApiKey: Flow<String> = dataStore.data.map { it[GEMINI_API_KEY] ?: "" }
    val summaryInstruction: Flow<String> = dataStore.data.map {
        it[SUMMARY_INSTRUCTION]?.ifEmpty { defaultInstruction } ?: defaultInstruction
    }

    suspend fun updateGroqApiKey(key: String) {
        dataStore.edit { it[GROQ_API_KEY] = key }
    }

    suspend fun updateGeminiApiKey(key: String) {
        dataStore.edit { it[GEMINI_API_KEY] = key }
    }

    suspend fun updateSummaryInstruction(instruction: String) {
        dataStore.edit { it[SUMMARY_INSTRUCTION] = instruction }
    }

    /** 1回だけ読む（非Flow） */
    suspend fun getGroqApiKey(): String = groqApiKey.first()
    suspend fun getGeminiApiKey(): String = geminiApiKey.first()
    suspend fun getSummaryInstruction(): String = summaryInstruction.first()

    /** 初回起動時のみBuildConfigの値をDataStoreに書き込む */
    suspend fun ensureDefaults(defaultInstruction: String = "") {
        val current = dataStore.data.first()
        if (current[INITIALIZED] == true) return

        dataStore.edit { prefs ->
            if (BuildConfig.GROQ_API_KEY.isNotEmpty()) {
                prefs[GROQ_API_KEY] = BuildConfig.GROQ_API_KEY
            }
            if (BuildConfig.GEMINI_API_KEY.isNotEmpty()) {
                prefs[GEMINI_API_KEY] = BuildConfig.GEMINI_API_KEY
            }
            if (defaultInstruction.isNotEmpty()) {
                prefs[SUMMARY_INSTRUCTION] = defaultInstruction
            }
            prefs[INITIALIZED] = true
        }
    }
}
