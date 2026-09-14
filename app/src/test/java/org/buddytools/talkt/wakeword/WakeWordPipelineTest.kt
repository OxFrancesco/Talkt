package org.buddytools.talkt.wakeword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeWordPipelineTest {
    private class RecordingEngine(private val keywordOnFrame: Int = -1) : KeywordEngine {
        val frames = mutableListOf<Int>()
        var resets = 0

        override fun accept(samples: ShortArray, count: Int): String? {
            frames += samples[0].toInt()
            return if (frames.size == keywordOnFrame) "wake up" else null
        }

        override fun reset() {
            resets += 1
        }
    }

    @Test
    fun `quiet frames never reach the model`() {
        val engine = RecordingEngine()
        val pipeline = WakeWordPipeline(engine)

        repeat(10) { assertNull(pipeline.accept(frame(100), WakeWordModel.frameSamples)) }

        assertEquals(emptyList(), engine.frames)
    }

    @Test
    fun `the last quiet frames are replayed when speech starts`() {
        val engine = RecordingEngine()
        val pipeline = WakeWordPipeline(engine, preRollFrames = 3)

        listOf(101, 102, 103, 104, 105).forEach { pipeline.accept(frame(it), WakeWordModel.frameSamples) }
        pipeline.accept(frame(3_000), WakeWordModel.frameSamples)

        assertEquals(listOf(103, 104, 105, 3_000), engine.frames)
    }

    @Test
    fun `pre-roll is not replayed twice and every engine resets when the room goes quiet`() {
        val first = RecordingEngine()
        val second = RecordingEngine()
        val pipeline = WakeWordPipeline(listOf(first, second), WakeWordAudioGate(hangoverFrames = 2), preRollFrames = 2, gridOffsetSamples = 0)

        pipeline.accept(frame(110), WakeWordModel.frameSamples)
        pipeline.accept(frame(3_000), WakeWordModel.frameSamples)
        pipeline.accept(frame(3_001), WakeWordModel.frameSamples)
        repeat(3) { pipeline.accept(frame(120), WakeWordModel.frameSamples) }
        assertEquals(1, first.resets)
        assertEquals(1, second.resets)

        // Second utterance replays only the frames gated out after the reset.
        pipeline.accept(frame(130), WakeWordModel.frameSamples)
        pipeline.accept(frame(3_002), WakeWordModel.frameSamples)

        assertEquals(listOf(110, 3_000, 3_001, 120, 120, 120, 130, 3_002), first.frames)
        assertEquals(first.frames, second.frames)
    }

    @Test
    fun `the second engine starts its grid part-way into the pre-roll`() {
        val first = RecordingEngine()
        val second = object : KeywordEngine {
            val counts = mutableListOf<Int>()
            override fun accept(samples: ShortArray, count: Int): String? { counts += count; return null }
            override fun reset() = Unit
        }
        val pipeline = WakeWordPipeline(listOf(first, second), preRollFrames = 3, gridOffsetSamples = 2_560)

        repeat(3) { pipeline.accept(frame(100), WakeWordModel.frameSamples) }
        pipeline.accept(frame(3_000), WakeWordModel.frameSamples)

        assertEquals(listOf(100, 100, 100, 3_000), first.frames)
        // 2 560 samples skipped: the first pre-roll frame is dropped and the second loses 960 samples.
        assertEquals(listOf(640, 1_600, 1_600), second.counts)
    }

    @Test
    fun `whichever engine fires first wins`() {
        val quiet = RecordingEngine()
        val loud = RecordingEngine(keywordOnFrame = 1)
        val pipeline = WakeWordPipeline(listOf(quiet, loud), preRollFrames = 0)

        assertEquals("wake up", pipeline.accept(frame(3_000), WakeWordModel.frameSamples))
    }

    @Test
    fun `a keyword spotted inside the pre-roll is reported`() {
        val engine = RecordingEngine(keywordOnFrame = 1)
        val pipeline = WakeWordPipeline(engine, preRollFrames = 1)

        pipeline.accept(frame(100), WakeWordModel.frameSamples)

        assertEquals("wake up", pipeline.accept(frame(3_000), WakeWordModel.frameSamples))
        assertEquals(listOf(100), engine.frames)
    }

    private fun frame(amplitude: Int): ShortArray =
        ShortArray(WakeWordModel.frameSamples) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
}
