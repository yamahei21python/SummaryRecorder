package com.kohei.summaryrecorder.recorder

/**
 * オーディオフォーマット定数集約。
 * GaplessRecorder / RealAudioProvider 共用。
 */
object AudioConstants {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val READ_BUFFER = 4096
}
