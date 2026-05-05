package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `stop during split ensures all chunks are finalized exactly once`() = runTest {
        val provider = object : AudioProvider {
            @Volatile var active = true
            override fun start(): Boolean = true
            override fun read(buffer: ShortArray, size: Int): Int {
                return if (active) {
                    // Small delay to allow concurrency
                    Thread.sleep(1)
                    size // Fill buffer
                } else -1
            }
            override fun stop() { active = false }
            override fun release() { active = false }
        }

        recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 100, // Small chunks
            onChunkComplete = { index, _, _ -> recordedChunks.add(index) },
            audioProvider = provider,
            coroutineScope = this
        )

        recorder.start()
        
        // Wait for some chunks to be generated
        delay(50)
        
        // Concurrent stop
        recorder.stop()
        
        // Check if there are any duplicate indices
        val duplicates = recordedChunks.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate chunk indices found: $duplicates")
        
        // Ensure at least one chunk was created
        assertTrue(recordedChunks.isNotEmpty(), "At least one chunk should be recorded")
    }
}
