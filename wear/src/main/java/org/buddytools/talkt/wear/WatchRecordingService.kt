package org.buddytools.talkt.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.buddytools.talkt.wearbridge.WearProtocol
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class WatchRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            recording.set(false)
            return START_NOT_STICKY
        }
        if (!recording.compareAndSet(false, true)) return START_NOT_STICKY
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchRecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("recording", "Recording", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("Recording audio")
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop and send", stop).build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Talkt:watchRecording")
                .apply { acquire(70_000) }
        } catch (error: Exception) {
            recording.set(false)
            WatchStore.state.value = WatchState(error = "Allow microphone access to record")
            stopSelf()
            return START_NOT_STICKY
        }
        WatchStore.state.value = WatchState(recording = true)
        scope.launch { capture() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture() {
        val store = WatchStore(this)
        var part: java.io.File? = null
        var failure: String? = null
        try {
            part = store.newPart()
            val bufferSize = maxOf(4096, AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val input = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone unavailable" }
            FileOutputStream(part).use { output ->
                output.write(WearProtocol.wavHeader(0))
                val buffer = ByteArray(bufferSize)
                var bytes = 0
                while (recording.get() && bytes < WearProtocol.MAX_AUDIO_BYTES - 44) {
                    val count = input.read(buffer, 0, minOf(buffer.size, WearProtocol.MAX_AUDIO_BYTES - 44 - bytes))
                    check(count > 0) { "Microphone stopped. Audio kept." }
                    output.write(buffer, 0, count)
                    bytes += count
                }
                output.fd.sync()
            }
        } catch (error: Exception) {
            failure = error.message ?: "Recording stopped"
        } finally {
            recording.set(false)
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
            part?.let { runCatching { store.finish(it) }.onFailure { failure = "Audio kept. Reopen Talkt to recover it." } }
            if (wakeLock?.isHeld == true) wakeLock?.release()
            WatchStore.state.value = WatchState(error = failure)
            WatchSyncWorker.enqueue(this)
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        recording.set(false)
        runCatching { recorder?.stop() }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val STOP = "org.buddytools.talkt.STOP_WATCH_RECORDING" }
}
