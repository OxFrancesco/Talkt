package org.buddytools.talkt.transcription.local

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaRecognitionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage = ""

    private val binder = object : ILocalAsrService.Stub() {
        override fun transcribe(
            wav: ParcelFileDescriptor,
            durationMillis: Long,
            languageHint: String,
            callback: ILocalAsrCallback,
        ) {
            scope.launch {
                try {
                    if (durationMillis > MAX_LOCAL_DURATION_MILLIS) {
                        callback.onFailure(LocalAsrProtocol.INPUT_TOO_LONG, "recording_too_long")
                        return@launch
                    }
                    val samples = readSamples(wav)
                    val engine = ensureRecognizer(languageHint)
                    val decodeStartedAt = SystemClock.elapsedRealtime()
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        engine.decode(stream)
                        val result = engine.getResult(stream)
                        Log.i(TAG, "local decode: audio=${durationMillis}ms took ${SystemClock.elapsedRealtime() - decodeStartedAt}ms, ${result.text.length} chars")
                        if (result.text.isBlank()) {
                            callback.onNoSpeech()
                        } else {
                            callback.onSuccess(result.text.trim(), result.lang.orEmpty())
                        }
                    } finally {
                        stream.release()
                    }
                } catch (_: OutOfMemoryError) {
                    releaseRecognizer()
                    Log.w(TAG, "local decode out of memory")
                    callback.onFailure(LocalAsrProtocol.OUT_OF_MEMORY, "out_of_memory")
                } catch (error: Throwable) {
                    Log.w(TAG, "local decode failed: ${error.javaClass.simpleName}: ${error.message}")
                    callback.onFailure(
                        LocalAsrProtocol.INFERENCE_FAILED,
                        error.javaClass.simpleName.take(80),
                    )
                } finally {
                    wav.close()
                }
            }
        }

        override fun warmUp(languageHint: String) {
            scope.launch {
                runCatching { ensureRecognizer(languageHint) }
                    .onFailure { if (it is OutOfMemoryError) releaseRecognizer() }
            }
        }
    }

    private fun ensureRecognizer(languageHint: String): OfflineRecognizer {
        val normalizedLanguage = languageHint.trim().lowercase().takeIf { it in SUPPORTED_LANGUAGE_HINTS }.orEmpty()
        val existing = recognizer
        if (existing != null && recognizerLanguage == normalizedLanguage) return existing
        releaseRecognizer()
        val startedAt = SystemClock.elapsedRealtime()
        return createRecognizer(normalizedLanguage).also {
            recognizer = it
            recognizerLanguage = normalizedLanguage
            Log.i(TAG, "whisper model loaded in ${SystemClock.elapsedRealtime() - startedAt}ms (lang='$normalizedLanguage')")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) releaseRecognizer()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseRecognizer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        releaseRecognizer()
        scope.cancel()
        super.onDestroy()
    }

    private fun createRecognizer(language: String): OfflineRecognizer {
        val directory = "models/whisper-tiny"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$directory/tiny-encoder.int8.onnx",
                    decoder = "$directory/tiny-decoder.int8.onnx",
                    language = language,
                    task = "transcribe",
                    tailPaddings = 300,
                ),
                tokens = "$directory/tiny-tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        return OfflineRecognizer(assetManager = assets, config = config)
    }

    private fun readSamples(descriptor: ParcelFileDescriptor): FloatArray {
        val bytes = FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        require(bytes.size > WAV_HEADER_BYTES) { "empty_wav" }
        require(bytes.copyOfRange(0, 4).decodeToString() == "RIFF") { "invalid_wav" }
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pcm.remaining() / 2) { pcm.short / 32768.0f }
    }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
        recognizerLanguage = ""
    }

    private companion object {
        const val TAG = "Talkt"
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_BYTES = 44
        const val MAX_LOCAL_DURATION_MILLIS = 60_000L
        val SUPPORTED_LANGUAGE_HINTS = setOf("en", "it", "es", "fr", "de", "pt")
    }
}
