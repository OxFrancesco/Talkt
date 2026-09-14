package org.buddytools.talkt.wakeword

import kotlin.math.sqrt

internal data class WakeWordGateDecision(
    val shouldProcess: Boolean,
    /** True on the very frame that turned a quiet room into speech. */
    val opened: Boolean = false,
    val resetEngine: Boolean = false,
)

/**
 * Keeps the neural keyword model idle until the microphone contains likely speech.
 *
 * Speech is judged against the room: a frame counts when it is [noiseRatio] times louder than
 * the tracked noise floor, never below [minSpeechRms]. The floor is low on purpose: a "wake up"
 * spoken from across a room lands around 600 to 1 500 RMS on the VOICE_RECOGNITION source, and a
 * gate that never opens is the one failure the model cannot recover from.
 */
internal class WakeWordAudioGate(
    private val minSpeechRms: Double = 450.0,
    private val noiseRatio: Double = 2.2,
    private val loudRms: Double = 5_000.0,
    private val hangoverFrames: Int = 12,
) {
    private var noiseFloor = 200.0
    private var active = false
    private var quietFrames = 0

    init {
        require(hangoverFrames > 0)
    }

    fun accept(samples: ShortArray, count: Int = samples.size): WakeWordGateDecision {
        if (count <= 0) return WakeWordGateDecision(shouldProcess = active)
        var sumSquares = 0.0
        for (index in 0 until count) {
            val value = samples[index].toDouble()
            sumSquares += value * value
        }
        val rms = sqrt(sumSquares / count)
        val threshold = maxOf(minSpeechRms, noiseFloor * noiseRatio)
        val speech = rms >= loudRms || rms >= threshold

        if (!active) {
            if (speech) {
                active = true
                quietFrames = 0
                return WakeWordGateDecision(shouldProcess = true, opened = true)
            }
            noiseFloor = noiseFloor * 0.9 + rms.coerceAtLeast(MIN_NOISE_FLOOR) * 0.1
            return WakeWordGateDecision(shouldProcess = false)
        }

        quietFrames = if (speech) 0 else quietFrames + 1
        if (quietFrames > hangoverFrames) {
            active = false
            quietFrames = 0
            noiseFloor = rms.coerceAtLeast(MIN_NOISE_FLOOR)
            return WakeWordGateDecision(shouldProcess = false, resetEngine = true)
        }
        return WakeWordGateDecision(shouldProcess = true)
    }

    private companion object {
        const val MIN_NOISE_FLOOR = 20.0
    }
}
