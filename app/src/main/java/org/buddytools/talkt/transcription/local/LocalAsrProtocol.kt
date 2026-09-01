package org.buddytools.talkt.transcription.local

import org.buddytools.talkt.transcription.LocalFailure

internal object LocalAsrProtocol {
    const val MODEL_UNAVAILABLE = 1
    const val UNSUPPORTED_RUNTIME = 2
    const val INSUFFICIENT_MEMORY = 3
    const val INPUT_TOO_LONG = 4
    const val OUT_OF_MEMORY = 5
    const val ENGINE_PROCESS_DIED = 6
    const val INFERENCE_FAILED = 7

    fun failure(code: Int): LocalFailure = when (code) {
        MODEL_UNAVAILABLE -> LocalFailure.ModelUnavailable
        UNSUPPORTED_RUNTIME -> LocalFailure.UnsupportedRuntime
        INSUFFICIENT_MEMORY -> LocalFailure.InsufficientMemory
        INPUT_TOO_LONG -> LocalFailure.InputTooLong
        OUT_OF_MEMORY -> LocalFailure.OutOfMemory
        ENGINE_PROCESS_DIED -> LocalFailure.EngineProcessDied
        else -> LocalFailure.InferenceFailed
    }
}
