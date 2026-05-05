package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GaplessRecorderConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()
    private val tempDir: File by lazy { tempFolder.root }

    private lateinit var recorder: GaplessRecorder
    private val recordedChunks = mutableListOf<Int>()

    @Before
    fun setUp() {
        recordedChunks.clear()
    }

    @Test
    fun `stop during split ensures all chunks are finalized exactly once`() = runTest(UnconfinedTestDispatcher()) {
        val provider = object : AudioProvider {
            @Volatile var active = true
            var readCount = 0
            override fun start(): Boolean = true
            override fun read(buffer: ShortArray, size: Int): Int {
                readCount++
                // 100回読んだら自動停止（無限ループ防止）
                if (readCount > 100) active = false
                return if (active) size else -1
            }
            override fun stop() { active = false }
            override fun release() { active = false }
        }

        recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 100, // 小さいチャンクで分割を誘発
            onChunkComplete = { index, _, _ -> recordedChunks.add(index) },
            audioProvider = provider,
            coroutineScope = this
        )

        recorder.start()
        
        // UnconfinedTestDispatcher なので、start() 内の launch が即座にある程度進む
        // その後 stop を呼ぶ
        recorder.stop()
        
        // 重複チェック
        val duplicates = recordedChunks.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate chunk indices found: $duplicates")
        
        // 少なくとも1つは作成されているはず
        assertTrue(recordedChunks.isNotEmpty(), "At least one chunk should be recorded")
    }
}
