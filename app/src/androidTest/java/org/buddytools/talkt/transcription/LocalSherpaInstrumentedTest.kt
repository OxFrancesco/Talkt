package org.buddytools.talkt.transcription

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.buddytools.talkt.transcription.local.LocalAsrWarmer
import org.buddytools.talkt.transcription.local.SherpaServiceLocalDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSherpaInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @After
    fun stopPrewarming() {
        instrumentation.runOnMainSync { LocalAsrWarmer.release() }
    }

    @Test
    fun transcribesEnglishAndItalianInThePrivateProcess() = runBlocking {
        val decoder = SherpaServiceLocalDecoder(target)
        instrumentation.runOnMainSync { LocalAsrWarmer.prewarm(target, "en") }

        for (fixture in listOf("english.wav", "italian.wav")) {
            val wav = instrumentation.context.assets.open(fixture).use { it.readBytes() }
            val clip = RecordedClip.create(target.cacheDir, wav)
            try {
                val result = withTimeout(30_000) { decoder.decode(clip) }
                assertTrue("$fixture should decode locally, got $result", result is LocalAttempt.Decoded)
                result as LocalAttempt.Decoded
                assertTrue("$fixture returned blank text", result.text.isNotBlank())
                val expected = if (fixture == "english.wav") "yellow lamps" else "ciao francesco"
                assertTrue("$fixture lost the expected speech: ${result.text}", result.text.lowercase().contains(expected))
                assertEquals("Parakeet does not report a detected language", null, result.language)
                Log.i("TalktLocalTest", "$fixture [${result.language}]: ${result.text}")
            } finally {
                clip.close()
            }
        }
    }

    @Test
    fun silenceIsTerminalAndNeverReachesTheRemoteDecoder() = runBlocking {
        val clip = RecordedClip.create(target.cacheDir, pcmWav(ByteArray(5 * 16_000 * 2)))
        var remoteCalled = false
        val transcriber = LocalFirstTranscriber(SherpaServiceLocalDecoder(target), RemoteDecoder {
            remoteCalled = true
            Transcript("unexpected remote result", TranscriptSource.Gemini)
        })
        try {
            val error = runCatching { withTimeout(30_000) { transcriber.transcribe(clip) } }.exceptionOrNull()
            assertTrue("Expected NoSpeechException, got $error", error is NoSpeechException)
            assertFalse(remoteCalled)
        } finally {
            clip.close()
        }
    }

    @Test
    fun handlesSixtySecondsAndReloadsAfterMemoryTrim() = runBlocking {
        val source = instrumentation.context.assets.open("english.wav").use { it.readBytes() }.drop(44).toByteArray()
        val pcm = ByteArray(60 * 16_000 * 2) { source[it % source.size] }
        val clip = RecordedClip.create(target.cacheDir, pcmWav(pcm))
        val decoder = SherpaServiceLocalDecoder(target)
        instrumentation.runOnMainSync { LocalAsrWarmer.prewarm(target, "it") }
        try {
            val first = withTimeout(30_000) { decoder.decode(clip) }
            assertTrue("60 seconds failed: $first", first is LocalAttempt.Decoded)
            instrumentation.uiAutomation.executeShellCommand(
                "am send-trim-memory ${target.packageName}:local_asr RUNNING_LOW"
            ).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            val second = withTimeout(30_000) { decoder.decode(clip) }
            assertTrue("Decode after trim failed: $second", second is LocalAttempt.Decoded)
            assertEquals((first as LocalAttempt.Decoded).text, (second as LocalAttempt.Decoded).text)
        } finally {
            clip.close()
        }
    }

    @Test
    fun processDeathFallsBackWithTheSameClipAndTheNextCaptureRecovers() = runBlocking {
        val wav = instrumentation.context.assets.open("italian.wav").use { it.readBytes() }
        val clip = RecordedClip.create(target.cacheDir, wav)
        val decoder = SherpaServiceLocalDecoder(target)
        var received: RecordedClip? = null
        val transcriber = LocalFirstTranscriber(decoder, RemoteDecoder {
            received = it
            Transcript("fallback after process death", TranscriptSource.Gemini)
        })
        try {
            val pending = async { withTimeout(30_000) { transcriber.transcribe(clip) } }
            val manager = target.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val asrProcess = withTimeout(5_000) {
                var process: ActivityManager.RunningAppProcessInfo? = null
                while (process == null) {
                    delay(10)
                    process = manager.runningAppProcesses?.find { it.processName == "${target.packageName}:local_asr" }
                }
                process
            }
            Process.killProcess(asrProcess.pid)
            assertEquals(TranscriptSource.Gemini, pending.await().source)
            assertTrue("Fallback must reuse the captured WAV", received === clip)
            val recovered = withTimeout(30_000) { decoder.decode(clip) }
            assertTrue("Next capture did not recover: $recovered", recovered is LocalAttempt.Decoded)
        } finally {
            clip.close()
        }
    }

    private fun pcmWav(pcm: ByteArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".encodeToByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".encodeToByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        buffer.put("data".encodeToByteArray()).putInt(pcm.size).put(pcm)
        return buffer.array()
    }
}
