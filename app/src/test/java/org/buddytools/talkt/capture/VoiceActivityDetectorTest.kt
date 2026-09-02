package org.buddytools.talkt.capture

import org.junit.Assert.assertEquals
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

        // Five seconds of silence is well past the 1s trailing window but short of the no-speech cutoff.
        assertFalse(detector.acceptPcm16Le(pcm(samples = 50, amplitude = 0), 100))
    }

    @Test
    fun `gives up when nothing crosses the speech threshold`() {
        // A silenced mic (another app owns the input) delivers zeros forever. Regression for
        // the 95-second capture on 2026-09-02 that never left Listening.
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10, noSpeechTimeoutMillis = 8_000)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 79, amplitude = 0), 158))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 0), 2))
        assertFalse(detector.heardSpeech)
    }

    @Test
    fun `steady room noise above the fixed threshold is not speech`() {
        // Measured on the Fold on 2026-09-02: every buffer sat above 450 RMS with nobody talking,
        // so the trailing-silence timer never started and the capture ran to the 60s cap.
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10, noSpeechTimeoutMillis = 8_000)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 79, amplitude = 900), 158))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 900), 2))
        assertFalse(detector.heardSpeech)
        assertEquals(StopReason.NoSpeech, detector.stopReason)
    }

    @Test
    fun `speech is judged against the measured noise floor`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 10, amplitude = 900), 20))
        assertFalse(detector.acceptPcm16Le(pcm(samples = 10, amplitude = 5_000), 20))
        assertTrue(detector.heardSpeech)
        assertFalse(detector.acceptPcm16Le(pcm(samples = 9, amplitude = 900), 18))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 900), 2))
        assertEquals(StopReason.TrailingSilence, detector.stopReason)
    }

    @Test
    fun `hard cap stops a recording that never goes quiet`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10, maxDurationMillis = 60_000)

        // Half-second bursts with half-second gaps: speech never pauses long enough to trigger the trailing timer.
        repeat(59) {
            assertFalse(detector.acceptPcm16Le(pcm(samples = 5, amplitude = 5_000), 10))
            assertFalse(detector.acceptPcm16Le(pcm(samples = 5, amplitude = 0), 10))
        }
        assertFalse(detector.acceptPcm16Le(pcm(samples = 5, amplitude = 5_000), 10))
        assertTrue(detector.acceptPcm16Le(pcm(samples = 5, amplitude = 0), 10))
        assertTrue(detector.heardSpeech)
        assertEquals(StopReason.MaxDuration, detector.stopReason)
    }

    @Test
    fun `speech that starts on the first buffer is recognised once a pause reveals the floor`() {
        val detector = VoiceActivityDetector(silenceTimeoutMillis = 1_000, sampleRate = 10)

        assertFalse(detector.acceptPcm16Le(pcm(samples = 20, amplitude = 3_000), 40))
        assertFalse(detector.heardSpeech)
        assertFalse(detector.acceptPcm16Le(pcm(samples = 9, amplitude = 200), 18))
        assertTrue(detector.heardSpeech)
        assertTrue(detector.acceptPcm16Le(pcm(samples = 1, amplitude = 200), 2))
        assertEquals(StopReason.TrailingSilence, detector.stopReason)
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
