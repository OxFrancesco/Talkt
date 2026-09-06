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
    ): SherpaWakeWordEngine {
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
            keywordsScore = 1.5f,
            keywordsThreshold = 0.20f,
            numTrailingBlanks = 2,
        )
        return SherpaWakeWordEngine(
            spotter = KeywordSpotter(context.assets, config),
            additionalKeywords = additionalKeywords,
        )
    }
}

internal class SherpaWakeWordEngine(
    private val spotter: KeywordSpotter,
    additionalKeywords: String,
) : AutoCloseable {
    private val stream: OnlineStream = spotter.createStream(additionalKeywords)
    private val floatFrame = FloatArray(WakeWordModel.frameSamples)

    fun accept(samples: ShortArray, count: Int): String? {
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

    fun reset() {
        spotter.reset(stream)
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
