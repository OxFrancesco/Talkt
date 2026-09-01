package org.buddytools.talkt.transcription

import kotlinx.coroutines.CancellationException

internal class LocalFirstTranscriber(
    private val local: LocalDecoder,
    private val remote: RemoteDecoder,
) : Transcriber {
    override suspend fun transcribe(clip: RecordedClip): Transcript {
        val localResult = try {
            local.decode(clip)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            LocalAttempt.Failed(LocalFailure.OutOfMemory)
        } catch (_: Throwable) {
            LocalAttempt.Failed(LocalFailure.InferenceFailed)
        }

        return when (localResult) {
            is LocalAttempt.Decoded -> {
                val text = localResult.text.trim()
                if (text.isEmpty()) throw NoSpeechException()
                Transcript(text, TranscriptSource.OnDevice, localResult.language?.ifBlank { null })
            }
            LocalAttempt.NoSpeech -> throw NoSpeechException()
            is LocalAttempt.Failed -> try {
                remote.decode(clip)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (remoteError: Throwable) {
                throw TranscriptionUnavailableException(localResult.reason.name, remoteError)
            }
        }
    }
}
