package org.buddytools.talkt.wakeword

/** What the pipeline needs from a keyword model; [SherpaWakeWordEngine] is the real one. */
internal interface KeywordEngine {
    /** Returns the keyword once it is spotted, otherwise null. */
    fun accept(samples: ShortArray, count: Int): String?
    fun reset()
}

/**
 * Microphone frames go through the [WakeWordAudioGate] first; only likely speech reaches the
 * model. Two things the bare gate gets wrong, both fixed here:
 *
 * - The frame that opens the gate is already speech, so the model never hears the moment before
 *   "wake"; the Zipformer misses quiet phrases without that left context. The pipeline keeps the
 *   last [preRollFrames] gated-out frames and replays them the moment the gate opens.
 * - The encoder scores audio in 450 ms windows that advance 320 ms at a time, so a quiet phrase
 *   whose syllables land on a window edge gets too little context and is missed, while the same
 *   phrase 160 ms later is caught. Every engine after the first is started [gridOffsetSamples]
 *   later into the pre-roll, which shifts its window grid by that much; whichever grid fits fires.
 *   The extra engines only run while the gate is open.
 */
internal class WakeWordPipeline(
    private val engines: List<KeywordEngine>,
    private val gate: WakeWordAudioGate = WakeWordAudioGate(),
    private val preRollFrames: Int = DEFAULT_PRE_ROLL_FRAMES,
    private val gridOffsetSamples: Int = DEFAULT_GRID_OFFSET_SAMPLES,
) {
    constructor(engine: KeywordEngine, gate: WakeWordAudioGate = WakeWordAudioGate(), preRollFrames: Int = DEFAULT_PRE_ROLL_FRAMES) :
        this(listOf(engine), gate, preRollFrames)

    private val preRoll = ArrayDeque<ShortArray>(preRollFrames)

    init {
        require(engines.isNotEmpty())
    }

    fun accept(samples: ShortArray, count: Int): String? {
        val decision = gate.accept(samples, count)
        if (decision.resetEngine) engines.forEach(KeywordEngine::reset)
        if (!decision.shouldProcess) {
            remember(samples, count)
            return null
        }
        if (decision.opened) {
            val replayed = preRoll.toList()
            preRoll.clear()
            engines.forEachIndexed { index, engine ->
                replay(engine, replayed, skipSamples = index * gridOffsetSamples)?.let { return it }
            }
        }
        for (engine in engines) {
            engine.accept(samples, count)?.let { return it }
        }
        return null
    }

    private fun replay(engine: KeywordEngine, frames: List<ShortArray>, skipSamples: Int): String? {
        var toSkip = skipSamples
        for (frame in frames) {
            if (toSkip >= frame.size) {
                toSkip -= frame.size
                continue
            }
            val slice = if (toSkip == 0) frame else frame.copyOfRange(toSkip, frame.size)
            toSkip = 0
            engine.accept(slice, slice.size)?.let { return it }
        }
        return null
    }

    private fun remember(samples: ShortArray, count: Int) {
        if (preRollFrames == 0 || count <= 0) return
        if (preRoll.size == preRollFrames) preRoll.removeFirst()
        preRoll.addLast(samples.copyOf(count))
    }

    companion object {
        /** 600 ms at 100 ms frames: more than one encoder window of left context, no added lag. */
        const val DEFAULT_PRE_ROLL_FRAMES = 6
        /** Half an encoder shift (320 ms) at 16 kHz. */
        const val DEFAULT_GRID_OFFSET_SAMPLES = 2_560
        const val DEFAULT_ENGINES = 2
    }
}
