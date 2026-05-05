package com.kohei.summaryrecorder.audio

/**
 * デバッグ/テスト用設定。
 * E2E時は debugMode=true で全てのモックが有効になる。
 */
object DebugConfig {
    @Volatile
    var debugMode: Boolean = false

    /** デバッグ時チャンクサイズ(100KB ≒ 3秒)。本番=19MB */
    const val DEBUG_CHUNK_BYTES = 100L * 1024
    /** 本番チャンクサイズ(19MB ≒ 10分) */
    const val PRODUCTION_CHUNK_BYTES = 19L * 1024 * 1024
}
