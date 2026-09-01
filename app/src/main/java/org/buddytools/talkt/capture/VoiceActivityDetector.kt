package org.buddytools.talkt.capture

import kotlin.math.sqrt

internal class VoiceActivityDetector(
    silenceTimeoutMillis: Long,
    private val sampleRate: Int = 16_000,
    private val speechRmsThreshold: Double = 450.0,
) {
    private val silenceSamples = silenceTimeoutMillis * sampleRate / 1_000
    private var processedSamples = 0L
    private var lastSpeechSample = 0L
    private var heardSpeech = false
    private var completed = false

    init {
        require(silenceTimeoutMillis > 0)
    }

    fun acceptPcm16Le(bytes: ByteArray, count: Int): Boolean {
        if (completed || count < 2) return completed
        val usableCount = count - (count % 2)
        var sumSquares = 0.0
        var index = 0
        while (index < usableCount) {
            val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
            val signed = sample.toShort().toDouble()
            sumSquares += signed * signed
            index += 2
        }

        val sampleCount = usableCount / 2
        processedSamples += sampleCount
        val rms = sqrt(sumSquares / sampleCount)
        if (rms >= speechRmsThreshold) {
            heardSpeech = true
            lastSpeechSample = processedSamples
        }

        completed = heardSpeech && processedSamples - lastSpeechSample >= silenceSamples
        return completed
    }
}
