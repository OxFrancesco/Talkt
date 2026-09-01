package org.buddytools.talkt.transcription.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the `:local_asr` process and its Whisper model warm.
 *
 * Loading the model costs ~10s of cold start (process fork + ~150 MB of ONNX assets),
 * so [prewarm] is called as soon as a capture begins: the model loads while the user
 * is still speaking. The binding is held for [IDLE_TTL_MILLIS] after the last prewarm
 * so consecutive dictations reuse the loaded recognizer, then released to give the
 * memory back. Main thread only.
 */
object LocalAsrWarmer {
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { unbind() }
    private var appContext: Context? = null
    private var connection: ServiceConnection? = null
    private var service: ILocalAsrService? = null
    private var pendingHint = ""

    fun prewarm(context: Context, languageHint: String) {
        appContext = context.applicationContext
        pendingHint = languageHint
        handler.removeCallbacks(release)
        handler.postDelayed(release, IDLE_TTL_MILLIS)

        val bound = service
        if (bound != null) {
            if (runCatching { bound.warmUp(languageHint) }.isFailure) rebind()
            return
        }
        if (connection == null) bind()
    }

    private fun bind() {
        val context = appContext ?: return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = ILocalAsrService.Stub.asInterface(binder)
                runCatching { service?.warmUp(pendingHint) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }

            override fun onBindingDied(name: ComponentName) {
                unbind()
            }
        }
        connection = conn
        val bound = runCatching {
            context.bindService(Intent(context, SherpaRecognitionService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            connection = null
        }
    }

    private fun rebind() {
        unbind()
        handler.postDelayed(release, IDLE_TTL_MILLIS)
        bind()
    }

    private fun unbind() {
        handler.removeCallbacks(release)
        val context = appContext
        connection?.let { conn -> context?.let { runCatching { it.unbindService(conn) } } }
        connection = null
        service = null
    }

    private const val IDLE_TTL_MILLIS = 5 * 60 * 1_000L
}
