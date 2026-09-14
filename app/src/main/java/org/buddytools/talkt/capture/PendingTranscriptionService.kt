package org.buddytools.talkt.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.buddytools.talkt.R
import org.buddytools.talkt.agent.AgentRunService
import org.buddytools.talkt.cabinet.CabinetActivity
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.transcription.NoSpeechException
import org.buddytools.talkt.transcription.RecordedClip
import org.buddytools.talkt.transcription.TranscriptionGraph
import java.io.File

/**
 * Transcribes recordings the capture bubble could not finish itself: the bubble was destroyed
 * mid-recording, the process died, or the transcription failed. Successful transcripts are
 * copied, archived in the Cabinet and announced with a notification. Anything that still fails
 * stays on disk so the Cabinet can retry it by hand.
 */
class PendingTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private var inFlight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = RecordingStore(filesDir)
        val files = intent?.getStringExtra(EXTRA_FILE)?.let { listOf(File(it)) }
            ?: store.pending().map { it.file }
        if (files.isEmpty()) {
            if (inFlight == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        inFlight += 1
        promote(progressNotification(files.size))
        val source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty()

        scope.launch {
            queue.withLock {
                files.forEach { file -> transcribe(file, source) }
            }
            if (--inFlight == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun transcribe(file: File, source: String) {
        if (!file.exists()) return
        val settings = TalktSettings(this)
        val accountId = org.buddytools.talkt.auth.TalktAuth.userId
        val startedAt = SystemClock.elapsedRealtime()
        val clip = runCatching { RecordedClip.open(file) }.getOrElse {
            Log.w(TAG, "pending recording ${file.name} is unreadable, dropping it: ${it.message}")
            file.delete()
            return
        }
        Log.i(TAG, "pending transcription started for ${file.name} (${clip.durationMillis}ms)")
        runCatching { TranscriptionGraph.create(this, settings).transcribe(clip) }
            .onSuccess { transcript ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "pending transcription of ${file.name} done in ${elapsed}ms (${transcript.text.length} chars)")
                runCatching {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Talkt transcript", transcript.text))
                }
                runCatching { TranscriptStore(this, accountId).append(transcript.text, kind = "copy", source = source.ifBlank { "recovered" }) }
                settings.latestResult = transcript.text
                clip.close()
                notify(RESULT_ID_BASE + file.hashCode(), resultNotification("Recording transcribed", transcript.text))
            }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                if (error is NoSpeechException) {
                    Log.i(TAG, "pending recording ${file.name} had no speech, dropping it")
                    clip.close()
                    return@onFailure
                }
                Log.w(TAG, "pending transcription of ${file.name} failed: ${error.message}")
                notify(
                    RESULT_ID_BASE + file.hashCode(),
                    resultNotification(
                        "Recording kept",
                        "${error.message ?: "Talkt could not transcribe it"}. Open the Cabinet to retry.",
                    ),
                )
            }
    }

    private fun progressNotification(count: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (count == 1) "Transcribing saved recording" else "Transcribing $count saved recordings")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setContentIntent(openCabinet())
            .build()

    private fun resultNotification(title: String, body: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openCabinet())
            .build()

    private fun openCabinet(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, CabinetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: Notification) {
        if (!AgentRunService.canNotify(this)) return
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Saved recordings", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val TAG = "Talkt"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "talkt.recordings"
        private const val ONGOING_ID = 51
        private const val RESULT_ID_BASE = 5_100

        /**
         * Transcribes one kept recording, or every pending one when [file] is null. Returns false
         * when Android refused the foreground start (app in background too long); the recording
         * stays in the Cabinet either way.
         */
        fun start(context: Context, file: File? = null, source: String = ""): Boolean {
            val intent = Intent(context, PendingTranscriptionService::class.java)
                .putExtra(EXTRA_SOURCE, source)
            file?.let { intent.putExtra(EXTRA_FILE, it.absolutePath) }
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start the background transcriber: ${it.message}") }
                .isSuccess
        }
    }
}
