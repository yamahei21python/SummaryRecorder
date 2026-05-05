package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bug fix verify: cancel() → cancelAndJoin() レースコンディション修正
 *
 * stop()内でrecordingJob?.cancel()のみだとfinalizeCurrentChunk()と
 * 並行書込みが競合し、WAVヘッダ破損の可能性あり。
 * cancelAndJoin()でjob完了を待ってからfinalizeすることで安全に確定。
 */
class GaplessRecorderCancelRaceTest {

    @TempDir
    lateinit var tempDir: File

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 録音中にデータを供給し続けるprovider */
    private fun createContinuousProvider(readCount: Int): AudioProvider = object : AudioProvider {
        private var count = 0
        override fun start(): Boolean = true
        override fun stop() {}
        override fun release() {}
        override fun read(buffer: ShortArray, size: Int): Int {
            if (count >= readCount) return -1
            for (i in buffer.indices) buffer[i] = (count % 256).toShort()
            count++
            return size
        }
    }

    @Test
    fun `stop with cancelAndJoin produces valid WAV during active recording`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        // readCount=100: 十分長く書込み中にstop()を呼ぶ
        val provider = createContinuousProvider(readCount = 100)

        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 256L, // 小さくして頻繁にfinalize
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = provider,
            coroutineScope = testScope
        )

        recorder.start()

        // 少しデータを書かせてからstop() — レース条件の再現
        delay(50)
        recorder.stop()

        // 少なくとも1チャンク生成
        val wavFiles = tempDir.listFiles()?.filter { it.name.startsWith("chunk_") } ?: emptyList()
        assertTrue(wavFiles.isNotEmpty(), "stop後にWAVファイルが存在すべき")

        // 全ファイルが有効なRIFFヘッダを持つ
        wavFiles.forEach { file ->
            val bytes = file.readBytes()
            assertTrue(bytes.size >= 44, "${file.name}: ヘッダ最少44byte")
            assertEquals("RIFF", String(bytes, 0, 4))
        }
    }

    @Test
    fun `immediate stop after start produces valid WAV`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val provider = createContinuousProvider(readCount = 50)

        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 8192L,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = provider,
            coroutineScope = testScope
        )

        recorder.start()
        // 即座にstop — recordingJobはまだ実行中の可能性大
        recorder.stop()

        val wavFiles = tempDir.listFiles()?.filter { it.name.startsWith("chunk_") } ?: emptyList()
        assertTrue(wavFiles.isNotEmpty(), "即stopでもWAV生成")

        // data chunk size == ファイルサイズ - 44
        wavFiles.forEach { file ->
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            val dataLen = java.nio.ByteBuffer.wrap(bytes, 40, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            assertEquals(bytes.size - 44, dataLen, "${file.name}: dataLength一致")
        }
    }
}
