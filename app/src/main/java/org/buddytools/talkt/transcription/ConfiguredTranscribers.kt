package org.buddytools.talkt.transcription

import kotlinx.coroutines.CancellationException

internal class RemoteOnlyTranscriber(
    private val remote: RemoteDecoder,
) : Transcriber {
    override suspend fun transcribe(clip: RecordedClip): Transcript = remote.decode(clip)
}

internal class LocalOnlyTranscriber(
    private val local: LocalDecoder,
) : Transcriber {
    override suspend fun transcribe(clip: RecordedClip): Transcript {
        val result = try {
            local.decode(clip)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            LocalAttempt.Failed(LocalFailure.OutOfMemory)
        } catch (_: Throwable) {
            LocalAttempt.Failed(LocalFailure.InferenceFailed)
        }

        return when (result) {
            is LocalAttempt.Decoded -> {
                val text = result.text.trim()
                if (text.isEmpty()) throw NoSpeechException()
                Transcript(text, TranscriptSource.OnDevice, result.language?.ifBlank { null })
            }
            LocalAttempt.NoSpeech -> throw NoSpeechException()
            is LocalAttempt.Failed -> throw OnDeviceTranscriptionException(result.reason.name)
        }
    }
}
