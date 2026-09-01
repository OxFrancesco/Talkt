package org.buddytools.talkt.transcription

enum class TranscriptSource {
    OnDevice,
    Gemini,
}

data class Transcript(
    val text: String,
    val source: TranscriptSource,
    val language: String? = null,
)

fun interface Transcriber {
    suspend fun transcribe(clip: RecordedClip): Transcript
}

internal fun interface LocalDecoder {
    suspend fun decode(clip: RecordedClip): LocalAttempt
}

internal sealed interface LocalAttempt {
    data class Decoded(val text: String, val language: String?) : LocalAttempt
    data object NoSpeech : LocalAttempt
    data class Failed(val reason: LocalFailure) : LocalAttempt
}

internal enum class LocalFailure {
    ModelUnavailable,
    UnsupportedRuntime,
    InsufficientMemory,
    InputTooLong,
    OutOfMemory,
    EngineProcessDied,
    InferenceFailed,
}

internal fun interface RemoteDecoder {
    suspend fun decode(clip: RecordedClip): Transcript
}

class NoSpeechException : Exception("I didn't hear anything")

class OnDeviceTranscriptionException(reason: String) :
    Exception("On-device transcription could not finish ($reason). Select On-device first or Gemini in Talkt settings.")

class TranscriptionUnavailableException(
    localFailure: String,
    remoteCause: Throwable,
) : Exception(
    "On-device transcription failed ($localFailure), and Gemini could not finish: " +
        (remoteCause.message ?: "unknown error"),
    remoteCause,
)
