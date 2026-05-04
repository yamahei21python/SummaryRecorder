package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** 本番: AudioRecord 経由で端末マイクからPCM取得 */
class RealAudioProvider(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val bufferSize: Int = 4096
) : AudioProvider {

    private var audioRecord: AudioRecord? = null

    override fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) return false
        val buf = maxOf(minBuf, bufferSize)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            channelConfig, audioFormat, buf
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) return false
            it.startRecording()
        }
        return true
    }

    override fun read(buffer: ShortArray, size: Int): Int =
        audioRecord?.read(buffer, 0, size) ?: -1

    override fun stop() {
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
    }

    override fun release() {
        audioRecord?.release()
        audioRecord = null
    }
}
