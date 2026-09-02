package org.buddytools.talkt.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import org.buddytools.talkt.MainActivity
import org.buddytools.talkt.R
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the agent prompt after the capture bubble has already closed.
 *
 * The bubble only owns recording and transcription; the agent round trip can take
 * a minute or more, so it lives here as a foreground `dataSync` service and reports
 * back through a notification instead of holding the overlay open.
 */
class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)?.takeIf(String::isNotBlank)
        val source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty()
        if (text == null) {
            if (inFlight.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannels()
        val runId = inFlight.incrementAndGet()
        promoteToForeground(progressNotification(text))

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val settings = TalktSettings(this@AgentRunService)
            val store = TranscriptStore(this@AgentRunService)
            Log.i(TAG, "agent run $runId started (${text.length} chars)")
            runCatching { TalktApi(settings).prompt(text) }
                .onSuccess { reply ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    Log.i(TAG, "agent run $runId answered by ${if (reply.fallback) "fallback" else "primary"} in ${elapsed}ms")
                    runCatching { store.append(text, kind = "agent", source = source, reply = reply.text) }
                    settings.latestResult = reply.text
                    notify(
                        RESULT_ID_BASE + runId,
                        answerNotification(
                            title = "Talkt Answer!",
                            body = reply.text,
                            elapsedMillis = elapsed,
                        ),
                    )
                }
                .onFailure { error ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    Log.w(TAG, "agent run $runId failed after ${elapsed}ms: ${error.message}")
                    runCatching { store.append(text, kind = "agent", source = source, reply = null) }
                    notify(
                        RESULT_ID_BASE + runId,
                        answerNotification(
                            title = "Agent did not answer",
                            body = error.message ?: "Talkt could not reach your agent",
                            elapsedMillis = elapsed,
                        ),
                    )
                }
            if (inFlight.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(text: String): Notification =
        NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Asking your agent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()

    private fun answerNotification(title: String, body: String, elapsedMillis: Long): Notification =
        NotificationCompat.Builder(this, ANSWER_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("%.1fs".format(elapsedMillis / 1_000.0))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: Notification) {
        if (!canNotify(this)) return
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Agent in progress", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ANSWER_CHANNEL, "Agent answers", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val TAG = "Talkt"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SOURCE = "source"
        private const val PROGRESS_CHANNEL = "talkt.agent.progress"
        private const val ANSWER_CHANNEL = "talkt.agent.answer"
        private const val ONGOING_ID = 41
        private const val RESULT_ID_BASE = 4_100

        fun start(context: Context, text: String, source: String) {
            val intent = Intent(context, AgentRunService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SOURCE, source)
            ContextCompat.startForegroundService(context, intent)
        }

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
