package org.buddytools.talkt.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    @Test
    fun `stops after configured trailing silence`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 3_000, sampleRate = 10)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 10, amplitude = 2_000), 20))
        assertFalse(detector.acceptPcm16Le(pcm(samples = 29, amplitude = 0), 58))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 0), 2))
    }

    @Test
    fun `does not time out before speech begins`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 100, amplitude = 0), 200))
    }

    @Test
    fun `new speech resets the silence clock`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10)

        detector.acceptPcm16Le(pcm(samples = 10, amplitude = 2_000), 20)
        detector.acceptPcm16Le(pcm(samples = 9, amplitude = 0), 18)
        assertFalse(detector.acceptPcm16Le(pcm(samples = 2, amplitude = 2_000), 4))
        assertFalse(detector.acceptPcm16Le(pcm(samples = 9, amplitude = 0), 18))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 0), 2))
    }

    private fun pcm(samples: Int, amplitude: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        repeat(samples) { index ->
            bytes[index * 2] = (amplitude and 0xff).toByte()
            bytes[index * 2 + 1] = (amplitude shr 8).toByte()
        }
    }
}
