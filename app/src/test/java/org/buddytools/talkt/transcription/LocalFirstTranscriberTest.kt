package org.buddytools.talkt.transcription

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LocalFirstTranscriberTest {
    @Test
    fun localSuccessDoesNotCallGemini() = runBlocking {
        val clip = fixtureClip()
        var remoteCalls = 0
        val transcriber = LocalFirstTranscriber(
            local = LocalDecoder { LocalAttempt.Decoded(" Ciao mondo ", "it") },
            remote = RemoteDecoder {
                remoteCalls += 1
                Transcript("remote", TranscriptSource.Gemini)
            },
        )

        val result = transcriber.transcribe(clip)

        assertEquals("Ciao mondo", result.text)
        assertEquals(TranscriptSource.OnDevice, result.source)
        assertEquals("it", result.language)
        assertEquals(0, remoteCalls)
        clip.close()
    }

    @Test
    fun everyLocalFailureFallsBackWithTheSameClip() = runBlocking {
        for (failure in LocalFailure.entries) {
            val clip = fixtureClip()
            var received: RecordedClip? = null
            val transcriber = LocalFirstTranscriber(
                local = LocalDecoder { LocalAttempt.Failed(failure) },
                remote = RemoteDecoder {
                    received = it
                    Transcript("fallback", TranscriptSource.Gemini)
                },
            )

            val result = transcriber.transcribe(clip)

            assertSame(clip, received)
            assertEquals(TranscriptSource.Gemini, result.source)
            clip.close()
        }
    }

    @Test
    fun noSpeechDoesNotUpload() = runBlocking {
        val clip = fixtureClip()
        var remoteCalls = 0
        val transcriber = LocalFirstTranscriber(
            local = LocalDecoder { LocalAttempt.NoSpeech },
            remote = RemoteDecoder {
                remoteCalls += 1
                Transcript("remote", TranscriptSource.Gemini)
            },
        )

        assertFailsWith<NoSpeechException> { transcriber.transcribe(clip) }
        assertEquals(0, remoteCalls)
        clip.close()
    }

    @Test
    fun onDeviceOnlyNeverFallsBack() = runBlocking {
        val clip = fixtureClip()
        val transcriber = LocalOnlyTranscriber(
            local = LocalDecoder { LocalAttempt.Failed(LocalFailure.InsufficientMemory) },
        )

        assertFailsWith<OnDeviceTranscriptionException> { transcriber.transcribe(clip) }
        clip.close()
    }

    @Test
    fun geminiOnlyUsesRemoteDirectly() = runBlocking {
        val clip = fixtureClip()
        var received: RecordedClip? = null
        val transcriber = RemoteOnlyTranscriber(
            remote = RemoteDecoder {
                received = it
                Transcript("cloud", TranscriptSource.Gemini, "en")
            },
        )

        val result = transcriber.transcribe(clip)

        assertSame(clip, received)
        assertEquals(TranscriptSource.Gemini, result.source)
        clip.close()
    }

    private fun fixtureClip(): RecordedClip {
        val wav = ByteArray(46)
        "RIFF".encodeToByteArray().copyInto(wav, 0)
        "WAVE".encodeToByteArray().copyInto(wav, 8)
        val temporaryDirectory = requireNotNull(System.getProperty("java.io.tmpdir"))
        return RecordedClip.create(File(temporaryDirectory), wav)
    }
}
