package org.buddytools.talkt.transcription.local

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import org.buddytools.talkt.transcription.LocalAttempt
import org.buddytools.talkt.transcription.LocalDecoder
import org.buddytools.talkt.transcription.LocalFailure
import org.buddytools.talkt.transcription.RecordedClip
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class SherpaServiceLocalDecoder(
    private val context: Context,
    private val languageHint: String = "",
) : LocalDecoder {
    override suspend fun decode(clip: RecordedClip): LocalAttempt {
        capabilityFailure(clip)?.let { return LocalAttempt.Failed(it) }
        return request(clip)
    }

    private fun capabilityFailure(clip: RecordedClip): LocalFailure? {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return LocalFailure.UnsupportedRuntime
        if (clip.durationMillis > MAX_LOCAL_DURATION_MILLIS) return LocalFailure.InputTooLong

        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.lowMemory) return LocalFailure.InsufficientMemory

        val models = context.assets.list(ParakeetModel.DIRECTORY).orEmpty().toSet()
        if (!models.containsAll(ParakeetModel.requiredFiles)) return LocalFailure.ModelUnavailable
        return null
    }

    private suspend fun request(clip: RecordedClip): LocalAttempt =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            lateinit var connection: ServiceConnection

            fun finish(result: LocalAttempt) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { context.unbindService(connection) }
                if (continuation.isActive) continuation.resume(result)
            }

            val callback = object : ILocalAsrCallback.Stub() {
                override fun onSuccess(text: String, language: String) {
                    finish(LocalAttempt.Decoded(text, language.ifBlank { null }))
                }

                override fun onNoSpeech() {
                    finish(LocalAttempt.NoSpeech)
                }

                override fun onFailure(code: Int, detail: String) {
                    finish(LocalAttempt.Failed(LocalAsrProtocol.failure(code)))
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (!continuation.isActive) return
                    val descriptor = runCatching { clip.openReadOnly() }.getOrElse {
                        finish(LocalAttempt.Failed(LocalFailure.InferenceFailed))
                        return
                    }
                    try {
                        ILocalAsrService.Stub.asInterface(binder).transcribe(
                            descriptor,
                            clip.durationMillis,
                            languageHint,
                            callback,
                        )
                    } catch (_: Throwable) {
                        finish(LocalAttempt.Failed(LocalFailure.EngineProcessDied))
                    } finally {
                        descriptor.close()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    finish(LocalAttempt.Failed(LocalFailure.EngineProcessDied))
                }

                override fun onBindingDied(name: ComponentName) {
                    finish(LocalAttempt.Failed(LocalFailure.EngineProcessDied))
                }

                override fun onNullBinding(name: ComponentName) {
                    finish(LocalAttempt.Failed(LocalFailure.InferenceFailed))
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    runCatching { context.unbindService(connection) }
                }
            }

            val bound = context.bindService(
                Intent(context, SherpaRecognitionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) finish(LocalAttempt.Failed(LocalFailure.InferenceFailed))
        }

    private companion object {
        const val MAX_LOCAL_DURATION_MILLIS = 60_000L
    }
}
