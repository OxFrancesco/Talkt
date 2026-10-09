package org.buddytools.talkt.transcription

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.buddytools.talkt.data.TalktSettings
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class ParakeetNativeResult(
    val text: String,
    val backend: String,
    val device: Int,
    val loadMillis: Double,
    val preprocessMillis: Double,
    val encoderMillis: Double,
    val decodeMillis: Double,
)

internal object ParakeetNative {
    init { System.loadLibrary("talkt-parakeet") }
    external fun hexagonAvailable(): Boolean
    external fun transcribe(model: String, backend: String, libraries: String, cache: String, wav: String): ParakeetNativeResult
}

object ParakeetModels {
    private val importLock = Mutex()
    fun directory(context: Context) = File(context.filesDir, "parakeet").apply { mkdirs() }
    fun selected(context: Context, settings: TalktSettings): File? = settings.parakeetModelFile
        .takeIf { it.matches(Regex("[a-f0-9-]+\\.gguf")) }
        ?.let { File(directory(context), it) }
        ?.takeIf { it.isFile }

    suspend fun import(context: Context, settings: TalktSettings, uri: Uri, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            importLock.withLock {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Parakeet GGUF"
                val target = File(directory(context), "${UUID.randomUUID()}.gguf")
                val pending = File(directory(context), "${target.name}.part")
                try {
                    val limit = 2L * 1024 * 1024 * 1024
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        pending.outputStream().use { output ->
                            val magic = ByteArray(4)
                            require(input.read(magic) == 4 && magic.decodeToString() == "GGUF") { "Choose a Parakeet GGUF model" }
                            output.write(magic)
                            val buffer = ByteArray(1024 * 1024)
                            var total = 4L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= limit) { "The model is larger than 2 GB" }
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                            require(total > 1024) { "The model file is incomplete" }
                        }
                    } ?: error("Could not open the model")
                    check(pending.renameTo(target)) { "Could not store the model" }
                    settings.parakeetModelName = name
                    settings.parakeetModelFile = target.name
                } finally {
                    pending.delete()
                }
            }
        }
}

class ParakeetTranscriber(private val context: Context, private val settings: TalktSettings) : Transcriber {
    override suspend fun transcribe(clip: RecordedClip): Transcript = withContext(Dispatchers.IO) {
        queue.withLock {
            val model = ParakeetModels.selected(context, settings) ?: error("Import a Parakeet GGUF in Settings")
            val backend = settings.parakeetBackend
            check(backend != "hexagon" || ParakeetNative.hexagonAvailable()) { "This build does not include the Hexagon SDK backend" }
            val cache = File(context.cacheDir, "parakeet-opencl").apply { mkdirs() }
            val start = SystemClock.elapsedRealtime()
            val result = ParakeetNative.transcribe(model.absolutePath, backend, context.applicationInfo.nativeLibraryDir, cache.absolutePath, clip.file.absolutePath)
            coroutineContext.ensureActive()
            val elapsed = SystemClock.elapsedRealtime() - start
            val text = result.text.trim()
            if (text.isEmpty()) throw NoSpeechException()
            val device = when (result.device) { 2 -> "NPU"; 1 -> "GPU"; else -> "CPU" }
            val inference = result.preprocessMillis + result.encoderMillis + result.decodeMillis
            val label = if (result.backend == "CPU") "CPU" else "${result.backend} $device"
            val detail = "$label · ${"%.2f".format(elapsed / 1000.0)}s"
            Log.i("Talkt", "Parakeet requested=$backend resolved=${result.backend} device=$device loadMs=${result.loadMillis} preprocessMs=${result.preprocessMillis} encoderMs=${result.encoderMillis} decodeMs=${result.decodeMillis} wallMs=$elapsed audioMs=${clip.durationMillis}")
            Transcript(text, "Parakeet ${result.backend}", detail, inference)
        }
    }

    private companion object { val queue = Mutex() }
}
