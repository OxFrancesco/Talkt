package org.buddytools.talkt.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeWordModelInstrumentedTest {
    @Test
    fun detectsReferenceKeywordFromModelReferenceAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "kws_reference.wav")
        assertEquals(WakeWordModel.sampleRate, wave.sampleRate)

        val detected = detect(
            wave.samples,
            additionalKeywords = "▁ L IGHT ▁UP",
            useAudioGate = false,
        )

        assertNotNull("The model should detect its LIGHT UP reference phrase", detected)
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "kws_reference.wav")

        val detected = detect(wave.samples)

        assertNull("Unrelated continuous speech must not activate Talkt", detected)
    }

    @Test
    fun detectsWakeUpFromRecordedSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "wake_up.wav")
        assertEquals(WakeWordModel.sampleRate, wave.sampleRate)

        val detected = detect(wave.samples)

        assertNotNull("The local keyword model should detect the spoken wake phrase", detected)
        requireNotNull(detected)
        val acceptedMillis = detected.acceptedSamples * 1_000L / WakeWordModel.sampleRate
        Log.i("TalktTest", "wake fixture detected after ${acceptedMillis}ms of input audio")
        assertTrue(
            "Wake phrase detection took ${acceptedMillis}ms of input audio",
            acceptedMillis <= 1_600L,
        )
    }

    /**
     * The fixture is a close, loud "wake up" (RMS ~2 800). Real use is quieter, further away,
     * offset against the 100 ms frame grid and sitting on room noise. Every variant must still
     * trigger through the audio gate that fronts the model in [WakeWordService].
     */
    @Test
    fun detectsWakeUpWhenQuietFarOrNoisy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "wake_up.wav")
        val random = java.util.Random(7)
        // The fixture starts on the first sample; real microphones always have a moment of room
        // before the phrase, so every variant gets one (the model itself needs that left context).
        val variants = linkedMapOf(
            "half volume after 300ms of silence" to leading(scaled(wave.samples, 0.5f), 4_800, noiseRms = 0f, random),
            "half volume, 50ms offset" to leading(scaled(wave.samples, 0.5f), 800, noiseRms = 0f, random),
            "quarter volume after 1s of quiet room" to leading(scaled(wave.samples, 0.25f), 16_000, noiseRms = 150f, random),
            "half volume in noise" to leading(scaled(wave.samples, 0.5f), 8_000, noiseRms = 300f, random),
            "loud after 2s of room noise" to leading(wave.samples, 32_000, noiseRms = 400f, random),
        )

        val missed = variants.filterValues { detect(it) == null }.keys
        Log.i("TalktTest", "wake variants missed: $missed")
        assertTrue("Wake phrase missed for: $missed", missed.isEmpty())
    }

    /**
     * The Zipformer scores 450 ms windows every 320 ms, so a quiet phrase can be caught or missed
     * depending on where it lands on that grid. Two seeds, a whole shift cycle in 40 ms steps.
     * The one-grid and bare-model counts are logged so the second grid's value stays measurable.
     */
    @Test
    fun detectsWakeUpAtAnyFrameAlignment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "wake_up.wav")
        val offsetsMillis = (0 until 320 step 40).toList()
        val cases = linkedMapOf<String, FloatArray>()
        for (seed in listOf(11L, 23L)) {
            val random = java.util.Random(seed)
            for (offset in offsetsMillis) {
                val lead = 16_000 + offset * 16
                cases["quiet seed$seed +${offset}ms"] = leading(scaled(wave.samples, 0.25f), lead, noiseRms = 150f, random)
                cases["noisy seed$seed +${offset}ms"] = leading(scaled(wave.samples, 0.5f), lead, noiseRms = 300f, random)
            }
        }

        val missedOneGrid = cases.filterValues { detect(it, engines = 1) == null }.keys
        val missedBareModel = cases.filterValues { detect(it, useAudioGate = false) == null }.keys
        val missed = cases.filterValues { detect(it) == null }.keys
        Log.i(
            "TalktTest",
            "wake alignment sweep over ${cases.size} cases: pipeline missed ${missed.size} $missed; " +
                "one grid missed ${missedOneGrid.size}; bare model missed ${missedBareModel.size}",
        )
        assertTrue("Wake phrase missed for: $missed", missed.size <= cases.size / 10)
    }

    @Test
    fun stillIgnoresUnrelatedSpeechWhenQuiet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wave = WaveReader.readWave(instrumentation.context.assets, "kws_reference.wav")

        assertNull("Quiet unrelated speech must not activate Talkt", detect(scaled(wave.samples, 0.5f)))
    }

    private fun scaled(samples: FloatArray, gain: Float): FloatArray = FloatArray(samples.size) { samples[it] * gain }

    private fun noisy(samples: FloatArray, noiseRms: Float, random: java.util.Random): FloatArray =
        FloatArray(samples.size) { samples[it] + (random.nextGaussian() * noiseRms / 32_768.0).toFloat() }

    private fun leading(samples: FloatArray, leadSamples: Int, noiseRms: Float, random: java.util.Random): FloatArray =
        noisy(FloatArray(leadSamples) + samples, noiseRms, random)

    private fun detect(
        samples: FloatArray,
        additionalKeywords: String = "",
        useAudioGate: Boolean = true,
        engines: Int = WakeWordPipeline.DEFAULT_ENGINES,
    ): Detection? {
        var detected: Detection? = null
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        WakeWordModel.create(targetContext, additionalKeywords).use { model ->
            // Same gate + pre-roll + grid path the service runs, or the bare model to measure its own floor.
            val bare = model.engine().takeUnless { useAudioGate }
            val pipeline = WakeWordPipeline(model.engines(engines)).takeIf { useAudioGate }
            var offset = 0
            while (offset < samples.size && detected == null) {
                val count = minOf(WakeWordModel.frameSamples, samples.size - offset)
                val frame = ShortArray(count) { index ->
                    (samples[offset + index] * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort()
                }
                offset += count
                val keyword = pipeline?.accept(frame, count) ?: bare?.accept(frame, count)
                keyword?.let {
                    detected = Detection(it, offset)
                }
            }
        }
        return detected
    }

    private data class Detection(
        val keyword: String,
        val acceptedSamples: Int,
    )
}
