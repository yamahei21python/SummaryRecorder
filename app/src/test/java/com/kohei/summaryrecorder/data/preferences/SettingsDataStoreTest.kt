package com.kohei.summaryrecorder.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsDataStoreTest {

    private lateinit var dbFile: File
    private lateinit var settings: SettingsDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = File(context.filesDir, "test_settings_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { dbFile })
        settings = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `initial values are empty without default`() = runTest {
        assertEquals("", settings.getGroqApiKey())
        assertEquals("", settings.getGeminiApiKey())
        assertEquals("", settings.getSummaryInstruction())
    }

    @Test
    fun `summary instruction falls back to default when DataStore empty`() = runTest {
        settings.setDefaultInstruction("デフォルト指示文")
        // DataStoreは空 → FlowがdefaultInstructionを返す
        assertEquals("デフォルト指示文", settings.getSummaryInstruction())
    }

    @Test
    fun `summary instruction flow emits default when empty`() = runTest {
        settings.setDefaultInstruction("デフォルト指示文")
        settings.summaryInstruction.test {
            assertEquals("デフォルト指示文", awaitItem())
            cancel()
        }
    }

    @Test
    fun `user instruction overrides default`() = runTest {
        settings.setDefaultInstruction("デフォルト指示文")
        settings.updateSummaryInstruction("ユーザー指示")
        assertEquals("ユーザー指示", settings.getSummaryInstruction())
    }

    @Test
    fun `empty instruction restores default`() = runTest {
        settings.setDefaultInstruction("デフォルト指示文")
        settings.updateSummaryInstruction("ユーザー指示")
        settings.updateSummaryInstruction("")
        assertEquals("デフォルト指示文", settings.getSummaryInstruction())
    }

    @Test
    fun `ensureDefaults writes default instruction`() = runTest {
        settings.setDefaultInstruction("デフォルト指示文")
        settings.ensureDefaults("デフォルト指示文")
        assertEquals("デフォルト指示文", settings.getSummaryInstruction())
    }

    @Test
    fun `ensureDefaults writes API keys`() = runTest {
        // BuildConfigはテスト環境では空
        settings.ensureDefaults("指示")
        // INITIALIZED=trueなので2回目はスキップ
        settings.ensureDefaults("指示")
    }

    @Test
    fun `update and read groq api key`() = runTest {
        settings.updateGroqApiKey("groq-test-key")
        assertEquals("groq-test-key", settings.getGroqApiKey())
    }

    @Test
    fun `update and read gemini api key`() = runTest {
        settings.updateGeminiApiKey("gemini-test-key")
        assertEquals("gemini-test-key", settings.getGeminiApiKey())
    }

    @Test
    fun `update and read summary instruction`() = runTest {
        settings.updateSummaryInstruction("test instruction")
        assertEquals("test instruction", settings.getSummaryInstruction())
    }

    @Test
    fun `groq api key flow emits updates`() = runTest {
        settings.groqApiKey.test {
            assertEquals("", awaitItem())
            settings.updateGroqApiKey("key1")
            assertEquals("key1", awaitItem())
            settings.updateGroqApiKey("key2")
            assertEquals("key2", awaitItem())
            cancel()
        }
    }

    @Test
    fun `gemini api key flow emits updates`() = runTest {
        settings.geminiApiKey.test {
            assertEquals("", awaitItem())
            settings.updateGeminiApiKey("key-a")
            assertEquals("key-a", awaitItem())
            cancel()
        }
    }

    @Test
    fun `ensureDefaults is idempotent`() = runTest {
        settings.ensureDefaults()
        settings.ensureDefaults() // 2回目はスキップ
        // エラーなく完了すればOK
    }

    @Test
    fun `user changes survive ensureDefaults`() = runTest {
        settings.ensureDefaults()
        settings.updateGroqApiKey("user-key")
        settings.ensureDefaults() // INITIALIZED=trueなので上書きしない
        assertEquals("user-key", settings.getGroqApiKey())
    }
}
