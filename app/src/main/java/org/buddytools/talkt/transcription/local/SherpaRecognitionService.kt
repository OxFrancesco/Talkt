package org.buddytools.talkt.transcription.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
                    val engine = ensureRecognizer()
                    val decodeStartedAt = SystemClock.elapsedRealtime()
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        engine.decode(stream)
                        val result = engine.getResult(stream)
                        Log.i(TAG, "parakeet decode: audio=${durationMillis}ms took ${SystemClock.elapsedRealtime() - decodeStartedAt}ms, ${result.text.length} chars")
                        if (result.text.isBlank()) {
                            callback.onNoSpeech()
                        } else {
                            callback.onSuccess(result.text.trim(), result.lang.orEmpty())
                        }
                    } finally {
                        stream.release()
                    }
                } catch (_: InsufficientModelMemory) {
                    callback.onFailure(LocalAsrProtocol.INSUFFICIENT_MEMORY, "insufficient_memory")
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
                runCatching { ensureRecognizer() }
                    .onFailure { if (it is OutOfMemoryError) releaseRecognizer() }
            }
        }
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (!ParakeetModel.canLoad(memory.availMem, memory.lowMemory)) {
            Log.w(TAG, "parakeet load skipped: available=${memory.availMem / (1024 * 1024)}MiB, lowMemory=${memory.lowMemory}")
            throw InsufficientModelMemory()
        }
        val startedAt = SystemClock.elapsedRealtime()
        return createRecognizer().also {
            recognizer = it
            Log.i(TAG, "parakeet model loaded in ${SystemClock.elapsedRealtime() - startedAt}ms (automatic language)")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) {
            scope.launch { releaseRecognizer() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        scope.launch { releaseRecognizer() }
    }

    override fun onDestroy() {
        scope.launch {
            releaseRecognizer()
            scope.cancel()
        }
        super.onDestroy()
    }

    private fun createRecognizer(): OfflineRecognizer {
        val directory = ParakeetModel.DIRECTORY
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$directory/encoder.int8.onnx",
                    decoder = "$directory/decoder.int8.onnx",
                    joiner = "$directory/joiner.int8.onnx",
                ),
                tokens = "$directory/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "nemo_transducer",
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
    }

    private class InsufficientModelMemory : Exception()

    private companion object {
        const val TAG = "Talkt"
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_BYTES = 44
        const val MAX_LOCAL_DURATION_MILLIS = 60_000L
    }
}
