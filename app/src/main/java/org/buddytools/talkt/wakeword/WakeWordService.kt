package org.buddytools.talkt.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import org.buddytools.talkt.MainActivity
import org.buddytools.talkt.R
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.trigger.TalktVoiceInteractionService

class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loopLock = Any()
    private var listeningThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var listeningRequested = false

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        active = WeakReference(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }

        promote("Starting local wake-word detector…")
        val availability = WakeWordPolicy.availability(prerequisites(this))
        if (availability != WakeWordAvailability.Ready) {
            Log.i(TAG, "wake word stopped: $availability")
            shutdown()
            return START_NOT_STICKY
        }

        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopListening(waitForRelease = true)
        if (active.get() === this) active = WeakReference(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (destroyed || WakeWordPolicy.availability(prerequisites(this)) != WakeWordAvailability.Ready) return
        synchronized(loopLock) {
            if (listeningThread?.isAlive == true) return
            listeningRequested = true
            listeningThread = Thread(::listenLoop, "talkt-wake-word").also { it.start() }
        }
    }

    private fun listenLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var detection: Detection? = null
        var retryAfterMicrophoneBusy = false
        val initializedAt = SystemClock.elapsedRealtime()
        try {
            val minBytes = AudioRecord.getMinBufferSize(
                WakeWordModel.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSamples = maxOf(WakeWordModel.frameSamples, minBytes / 2)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWordModel.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSamples * 2,
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Wake-word microphone could not initialize"
            }
            synchronized(loopLock) {
                if (!listeningRequested) {
                    recorder.release()
                    return
                }
                audioRecord = recorder
            }
            recorder.startRecording()
            if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                throw MicrophoneBusyException()
            }

            WakeWordModel.create(this).use { model ->
                if (!listeningRequested) return
                val readyIn = SystemClock.elapsedRealtime() - initializedAt
                Log.i(TAG, "wake word listening phrase=\"${WakeWordModel.phrase}\" modelReadyMs=$readyIn")
                mainHandler.post { promote("Listening for \"${WakeWordModel.phrase}\"") }

                val samples = ShortArray(WakeWordModel.frameSamples)
                val pipeline = WakeWordPipeline(model.engines(WakeWordPipeline.DEFAULT_ENGINES))
                while (listeningRequested) {
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) error("Wake-word microphone disconnected")
                    if (count < 0) error("Wake-word microphone read failed: $count")
                    if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                        throw MicrophoneBusyException()
                    }
                    val keyword = pipeline.accept(samples, count)
                    if (keyword != null) {
                        val detectedAt = SystemClock.elapsedRealtime()
                        Log.i(TAG, "wake word detected keyword=\"$keyword\" elapsedRealtime=$detectedAt")
                        listeningRequested = false
                        detection = Detection(keyword, detectedAt)
                        break
                    }
                }
            }
        } catch (_: MicrophoneBusyException) {
            if (listeningRequested) {
                listeningRequested = false
                retryAfterMicrophoneBusy = true
                Log.i(TAG, "wake word paused: another app owns the microphone")
                mainHandler.post { promote("Paused · another app is using the microphone") }
            }
        } catch (error: Throwable) {
            if (listeningRequested) {
                Log.e(TAG, "wake word detector failed", error)
                mainHandler.post { promote("Wake word unavailable · open Talkt") }
            }
        } finally {
            synchronized(loopLock) {
                runCatching { audioRecord?.stop() }
                audioRecord?.release()
                audioRecord = null
                if (listeningThread === Thread.currentThread()) listeningThread = null
            }
        }

        detection?.let { detected -> mainHandler.post { invokeAssistant(detected) } }
        if (retryAfterMicrophoneBusy) {
            mainHandler.postDelayed(::startListening, MICROPHONE_BUSY_RETRY_MILLIS)
        }
    }

    private fun invokeAssistant(detection: Detection) {
        promote("Heard \"${WakeWordModel.phrase}\" · starting Talkt")
        if (!TalktAssistantBridge.showHotwordSession(detection.detectedAt)) {
            Log.w(TAG, "wake word detected but the assistant bridge is not ready")
            mainHandler.postDelayed(::startListening, BRIDGE_RETRY_MILLIS)
        }
    }

    private fun pauseForCapture(): Long {
        val startedAt = SystemClock.elapsedRealtime()
        stopListening(waitForRelease = true)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "wake word microphone released for capture in ${elapsed}ms")
        return elapsed
    }

    private fun stopListening(waitForRelease: Boolean) {
        val thread: Thread?
        synchronized(loopLock) {
            listeningRequested = false
            runCatching { audioRecord?.stop() }
            thread = listeningThread
        }
        if (waitForRelease && thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(MIC_RELEASE_TIMEOUT_MILLIS) }
        }
    }

    private fun shutdown() {
        stopListening(waitForRelease = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promote(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Talkt wake word")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wake word",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when Talkt is listening locally for its wake word"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private data class Detection(val keyword: String, val detectedAt: Long)
    private class MicrophoneBusyException : RuntimeException()

    companion object {
        private const val TAG = "Talkt"
        private const val CHANNEL_ID = "talkt-wake-word"
        private const val NOTIFICATION_ID = 29
        private const val ACTION_START = "org.buddytools.talkt.wakeword.START"
        private const val ACTION_STOP = "org.buddytools.talkt.wakeword.STOP"
        private const val MIC_RELEASE_TIMEOUT_MILLIS = 750L
        private const val BRIDGE_RETRY_MILLIS = 1_000L
        private const val MICROPHONE_BUSY_RETRY_MILLIS = 15_000L
        private var active = WeakReference<WakeWordService>(null)

        fun prerequisites(context: Context): WakeWordPrerequisites = WakeWordPrerequisites(
            enabled = TalktSettings(context).wakeWordEnabled,
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            activeAssistant = VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, TalktVoiceInteractionService::class.java),
            ),
            modelAvailable = WakeWordModel.isAvailable(context),
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            active.get()?.shutdown() ?: context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun pauseForCapture(): Long = active.get()?.pauseForCapture() ?: 0L

        fun resume(context: Context) {
            if (!TalktSettings(context).wakeWordEnabled) return
            active.get()?.startListening() ?: start(context)
        }
    }
}
