package org.buddytools.talkt.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

object WakeWordModel {
    const val phrase = "Wake up"
    const val sampleRate = 16_000
    const val frameSamples = 1_600

    private const val directory = "models/wake-word"
    private val requiredAssets = listOf(
        "$directory/encoder.int8.onnx",
        "$directory/decoder.onnx",
        "$directory/joiner.int8.onnx",
        "$directory/tokens.txt",
        "$directory/keywords.txt",
    )

    fun isAvailable(context: Context): Boolean = requiredAssets.all { path ->
        runCatching { context.assets.open(path).use { it.read() } }.isSuccess
    }

    internal fun create(
        context: Context,
        additionalKeywords: String = "",
    ): SherpaWakeWordModel {
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$directory/encoder.int8.onnx",
                decoder = "$directory/decoder.onnx",
                joiner = "$directory/joiner.int8.onnx",
            ),
            tokens = "$directory/tokens.txt",
            numThreads = 1,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2",
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0f),
            modelConfig = model,
            maxActivePaths = 4,
            keywordsFile = "$directory/keywords.txt",
            // keywords.txt carries the per-phrase boost (:2.0) and threshold (#0.15); these are
            // fallbacks for phrases without them. Tuned on the Fold with WakeWordModelInstrumentedTest.
            keywordsScore = 2.0f,
            keywordsThreshold = 0.15f,
            numTrailingBlanks = 1,
        )
        return SherpaWakeWordModel(
            spotter = KeywordSpotter(context.assets, config),
            additionalKeywords = additionalKeywords,
        )
    }
}

/** One loaded spotter; every [engine] is an independent decoding stream over the same weights. */
internal class SherpaWakeWordModel(
    private val spotter: KeywordSpotter,
    private val additionalKeywords: String,
) : AutoCloseable {
    private val streams = mutableListOf<OnlineStream>()

    fun engine(): SherpaWakeWordEngine {
        val stream = spotter.createStream(additionalKeywords).also { streams += it }
        return SherpaWakeWordEngine(spotter, stream)
    }

    fun engines(count: Int): List<SherpaWakeWordEngine> = List(count) { engine() }

    override fun close() {
        streams.forEach { it.release() }
        streams.clear()
        spotter.release()
    }
}

internal class SherpaWakeWordEngine(
    private val spotter: KeywordSpotter,
    private val stream: OnlineStream,
) : KeywordEngine {
    private val floatFrame = FloatArray(WakeWordModel.frameSamples)

    override fun accept(samples: ShortArray, count: Int): String? {
        if (count <= 0) return null
        require(count <= floatFrame.size)
        for (index in 0 until count) {
            floatFrame[index] = samples[index] / 32_768f
        }
        val waveform = if (count == floatFrame.size) floatFrame else floatFrame.copyOf(count)
        stream.acceptWaveform(waveform, WakeWordModel.sampleRate)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotBlank()) {
                spotter.reset(stream)
                return keyword
            }
        }
        return null
    }

    override fun reset() {
        spotter.reset(stream)
    }
}
