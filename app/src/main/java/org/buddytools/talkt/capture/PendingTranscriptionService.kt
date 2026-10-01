package org.buddytools.talkt.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
import org.buddytools.talkt.cabinet.CabinetActivity
import java.io.File

/**
 * Transcribes recordings the capture bubble could not finish itself: the bubble was destroyed
 * mid-recording, the process died, or the transcription failed. Successful transcripts are
 * copied, archived in History and announced with a notification. Anything that still fails
 * stays on disk so History can retry it by hand.
 */
class PendingTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private var inFlight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = intent?.getStringExtra(EXTRA_OWNER)
        if (owner == null || owner != org.buddytools.talkt.auth.TalktAuth.userId) {
            if (inFlight == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val store = RecordingStore(filesDir, owner)
        val files = intent?.getStringExtra(EXTRA_FILE)?.let { listOf(File(it)).filter { file -> file.canonicalFile.parentFile == store.directory.canonicalFile } }
            ?: store.pending().map { it.file }
        if (files.isEmpty()) {
            if (inFlight == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        inFlight += 1
        promote(progressNotification(files.size))

        scope.launch {
            queue.withLock {
                files.forEach { file -> SavedRecordingTranscriber.transcribe(this@PendingTranscriptionService, file, owner) }
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

    private fun progressNotification(count: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (count == 1) "Transcribing saved recording" else "Transcribing $count saved recordings")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setContentIntent(openCabinet())
            .build()

    private fun openCabinet(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, CabinetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        private const val EXTRA_OWNER = "owner"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "talkt.recordings"
        private const val ONGOING_ID = 51

        /**
         * Transcribes one kept recording, or every pending one when [file] is null. Returns false
         * when Android refused the foreground start (app in background too long); the recording
         * stays in History either way.
         */
        fun start(context: Context, file: File? = null, source: String = "", owner: String? = org.buddytools.talkt.auth.TalktAuth.userId): Boolean {
            if (owner == null || owner != org.buddytools.talkt.auth.TalktAuth.userId) return false
            val intent = Intent(context, PendingTranscriptionService::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_OWNER, owner)
            file?.let { intent.putExtra(EXTRA_FILE, it.absolutePath) }
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start the background transcriber: ${it.message}") }
                .isSuccess
        }
    }
}
