package com.kohei.summaryrecorder.di

import com.kohei.summaryrecorder.audio.DebugConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DebugModeHolder / ChunkSize: デバッグモード反映検証。
 *
 * 検証項目:
 * - DebugModeHolder: debugMode=true → isDebugMode=true
 * - DebugModeHolder: debugMode=false → isDebugMode=false
 * - ChunkSize: デバッグモード時 → DEBUG_CHUNK_BYTES
 * - ChunkSize: 本番モード時 → PRODUCTION_CHUNK_BYTES
 */
class ConfigModuleTest {

    @BeforeEach
    fun setUp() {
        DebugConfig.debugMode = false
    }

    @AfterEach
    fun tearDown() {
        DebugConfig.debugMode = false
    }

    @Test
    fun `DebugModeHolder returns true when debugMode is true`() {
        DebugConfig.debugMode = true
        assertTrue(DebugModeHolder().isDebugMode)
    }

    @Test
    fun `DebugModeHolder returns false when debugMode is false`() {
        DebugConfig.debugMode = false
        assertFalse(DebugModeHolder().isDebugMode)
    }

    @Test
    fun `ChunkSize returns debug bytes when debugMode`() {
        DebugConfig.debugMode = true
        val holder = DebugModeHolder()
        val chunkSize = ConfigModule.provideChunkSize(holder)
        assertEquals(DebugConfig.DEBUG_CHUNK_BYTES, chunkSize.bytes)
    }

    @Test
    fun `ChunkSize returns production bytes when not debugMode`() {
        DebugConfig.debugMode = false
        val holder = DebugModeHolder()
        val chunkSize = ConfigModule.provideChunkSize(holder)
        assertEquals(DebugConfig.PRODUCTION_CHUNK_BYTES, chunkSize.bytes)
    }

    @Test
    fun `ChunkSize value reflects runtime debugMode change`() {
        DebugConfig.debugMode = false
        val holder = DebugModeHolder()

        // 本番モード
        val production = ConfigModule.provideChunkSize(holder)
        assertEquals(DebugConfig.PRODUCTION_CHUNK_BYTES, production.bytes)

        // ランタイム切替 → 新しいHolderが必要（isDebugModeはgetterで参照）
        DebugConfig.debugMode = true
        val debugHolder = DebugModeHolder()
        val debug = ConfigModule.provideChunkSize(debugHolder)
        assertEquals(DebugConfig.DEBUG_CHUNK_BYTES, debug.bytes)
    }
}
