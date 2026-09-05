package org.buddytools.talkt.transcription.local

internal object ParakeetModel {
    const val DIRECTORY = "models/parakeet-tdt-v3"
    val requiredFiles = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    const val MIN_AVAILABLE_BYTES = 1792L * 1024 * 1024

    fun canLoad(availableBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && availableBytes >= MIN_AVAILABLE_BYTES
}
