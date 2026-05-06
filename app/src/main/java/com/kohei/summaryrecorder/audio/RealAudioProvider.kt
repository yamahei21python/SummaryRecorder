package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.recorder.AudioConstants
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** 本番: AudioRecord 経由で端末マイクからPCM取得 */
class RealAudioProvider(
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val channelConfig: Int = AudioConstants.CHANNEL_CONFIG,
    private val audioFormat: Int = AudioConstants.AUDIO_FORMAT,
    private val bufferSize: Int = AudioConstants.READ_BUFFER
) : AudioProvider {

    private var audioRecord: AudioRecord? = null

    @Volatile
    private var lastMaxAmplitude: Int = 0

    override fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) return false
        val buf = maxOf(minBuf, bufferSize)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            channelConfig, audioFormat, buf
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        audioRecord = record
        return true
    }

    override fun read(buffer: ShortArray, size: Int): Int {
        val read = audioRecord?.read(buffer, 0, size) ?: -1
        if (read > 0) {
            var max = 0
            for (i in 0 until read) {
                val v = if (buffer[i] >= 0) buffer[i].toInt() else (-buffer[i]).toInt()
                if (v > max) max = v
            }
            lastMaxAmplitude = max
        }
        return read
    }

    override fun stop() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w("RealAudioProvider", "stop failed", e)
        }
        lastMaxAmplitude = 0
    }

    override fun release() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("RealAudioProvider", "release failed", e)
        }
        audioRecord = null
        lastMaxAmplitude = 0
    }

    override fun getMaxAmplitude(): Int = if (audioRecord != null) lastMaxAmplitude else 0
}
