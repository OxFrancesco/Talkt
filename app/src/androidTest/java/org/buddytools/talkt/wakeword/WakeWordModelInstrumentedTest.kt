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

    private fun detect(
        samples: FloatArray,
        additionalKeywords: String = "",
        useAudioGate: Boolean = true,
    ): Detection? {
        var detected: Detection? = null
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        WakeWordModel.create(targetContext, additionalKeywords).use { engine ->
            val audioGate = WakeWordAudioGate().takeIf { useAudioGate }
            var offset = 0
            while (offset < samples.size && detected == null) {
                val count = minOf(WakeWordModel.frameSamples, samples.size - offset)
                val frame = ShortArray(count) { index ->
                    (samples[offset + index] * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort()
                }
                offset += count
                val gate = audioGate?.accept(frame, count)
                if (gate?.resetEngine == true) engine.reset()
                val keyword = if (gate?.shouldProcess != false) engine.accept(frame, count) else null
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
