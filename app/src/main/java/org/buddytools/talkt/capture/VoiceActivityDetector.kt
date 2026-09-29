package org.buddytools.talkt.capture

import kotlin.math.sqrt

/**
 * Decides when a capture is over. Three exits, checked on every PCM buffer:
 *  - trailing silence after speech ([silenceTimeoutMillis], the normal path),
 *  - no speech at all for [noSpeechTimeoutMillis] (a silenced or dead mic; see [heardSpeech]),
 *  - [maxDurationMillis] elapsed regardless (keeps each dictation short).
 *
 * Speech is judged against the room, not an absolute level: the noise floor is the quietest
 * buffer in the last [FLOOR_WINDOW_BUFFERS], and a buffer counts as speech when it is at least
 * [noiseRatio] times that floor (never below [minSpeechRms]). Anything at or above [loudRms]
 * is speech regardless, so dictation that starts on the very first buffer is caught before any
 * pause has revealed the floor. The first buffer is excluded from the floor because the mic
 * path is still settling and reads several times quieter than the room really is.
 */
internal class VoiceActivityDetector(
    silenceTimeoutMillis: Long,
    private val sampleRate: Int = 16_000,
    private val minSpeechRms: Double = 300.0,
    private val noiseRatio: Double = 2.5,
    private val loudRms: Double = 4_000.0,
    noSpeechTimeoutMillis: Long = DEFAULT_NO_SPEECH_TIMEOUT_MILLIS,
    maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
) {
    private val silenceSamples = silenceTimeoutMillis * sampleRate / 1_000
    private val noSpeechSamples = noSpeechTimeoutMillis * sampleRate / 1_000
    private val maxSamples = maxDurationMillis * sampleRate / 1_000
    private val recentRms = ArrayDeque<Double>(FLOOR_WINDOW_BUFFERS)
    private var processedSamples = 0L
    private var lastSpeechSample = 0L
    private var buffers = 0
    private var speechBuffers = 0
    private var previousRms = 0.0
    private var previousEnd = 0L
    private var previousWasSpeech = false

    /** Set once [acceptPcm16Le] returns true. */
    var stopReason: StopReason? = null
        private set
    var heardSpeech = false
        private set
    var peakRms = 0.0
        private set
    /** Quietest recent buffer; 0 until the mic has settled. */
    var noiseFloor = 0.0
        private set
    /** Share of PCM buffers judged as speech; near 1.0 means the room never went quiet. */
    val speechRatio: Double get() = if (buffers == 0) 0.0 else speechBuffers.toDouble() / buffers
    val processedMillis: Long get() = processedSamples * 1_000 / sampleRate

    init {
        require(silenceTimeoutMillis > 0)
        require(noSpeechTimeoutMillis > 0)
        require(maxDurationMillis > 0)
    }

    fun acceptPcm16Le(bytes: ByteArray, count: Int): Boolean {
        if (stopReason != null || count < 2) return stopReason != null
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
        buffers += 1
        val rms = sqrt(sumSquares / sampleCount)
        if (rms > peakRms) peakRms = rms

        if (buffers > 1) {
            if (recentRms.size == FLOOR_WINDOW_BUFFERS) recentRms.removeFirst()
            recentRms.addLast(rms)
            noiseFloor = maxOf(recentRms.min(), MIN_FLOOR_RMS)
        }
        val threshold = if (noiseFloor > 0) maxOf(minSpeechRms, noiseFloor * noiseRatio) else loudRms
        val speech = rms >= loudRms || rms >= threshold
        if (speech) {
            heardSpeech = true
            speechBuffers += 1
            lastSpeechSample = processedSamples
        } else if (!previousWasSpeech && buffers > 1 && previousRms >= threshold) {
            // The floor just dropped: the buffer before this pause was speech after all.
            heardSpeech = true
            lastSpeechSample = previousEnd
        }
        previousRms = rms
        previousEnd = processedSamples
        previousWasSpeech = speech

        stopReason = when {
            processedSamples >= maxSamples -> StopReason.MaxDuration
            heardSpeech -> StopReason.TrailingSilence.takeIf { processedSamples - lastSpeechSample >= silenceSamples }
            else -> StopReason.NoSpeech.takeIf { processedSamples >= noSpeechSamples }
        }
        return stopReason != null
    }

    companion object {
        const val DEFAULT_NO_SPEECH_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_MAX_DURATION_MILLIS = 60_000L
        /** 16 buffers of 250 ms: long enough to span a sentence, short enough to follow a changing room. */
        private const val FLOOR_WINDOW_BUFFERS = 16
        private const val MIN_FLOOR_RMS = 20.0
    }
}
