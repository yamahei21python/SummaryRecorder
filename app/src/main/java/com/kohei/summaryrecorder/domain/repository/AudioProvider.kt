package com.kohei.summaryrecorder.domain.repository

/** マイク入力抽象化。本番=RealAudioProvider, E2E=DummyAudioProvider */
interface AudioProvider {
    /** 録音開始。成功=true */
    fun start(): Boolean
    /** PCM16bit Short配列読込。戻り値=読込要素数、-1=終了 */
    fun read(buffer: ShortArray, size: Int): Int
    fun stop()
    fun release()
}