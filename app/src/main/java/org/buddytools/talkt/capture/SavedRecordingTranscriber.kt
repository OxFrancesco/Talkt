package org.buddytools.talkt.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.buddytools.talkt.R
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.cabinet.CabinetActivity
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.transcription.NoSpeechException
import org.buddytools.talkt.transcription.RecordedClip
import org.buddytools.talkt.transcription.TranscriptionGraph
import java.io.File
import java.util.UUID

object SavedRecordingTranscriber {
    private val queue = Mutex()

    suspend fun transcribe(context: Context, file: File, owner: String): Boolean = queue.withLock {
        if (!file.exists()) return@withLock true
        if (TalktAuth.userId != owner) return@withLock false
        val settings = TalktSettings(context)
        val archive = TranscriptStore(context, owner)
        val transcriptId = UUID.nameUUIDFromBytes("recording:${file.nameWithoutExtension}".toByteArray()).toString()
        if (archive.list().any { it.id == transcriptId }) {
            file.delete()
            return@withLock true
        }
        val started = SystemClock.elapsedRealtime()
        val clip = runCatching { RecordedClip.open(file) }.getOrElse {
            Log.w("Talkt", "pending recording ${file.name} is unreadable: ${it.message}")
            return@withLock false
        }
        try {
            val transcript = TranscriptionGraph.create(settings, owner).transcribe(clip)
            TalktAuth.ensureUser(owner)
            archive.append(transcript.text, kind = "transcript", source = transcript.source, id = transcriptId)
            Log.i("Talkt", "pending transcription of ${file.name} done in ${SystemClock.elapsedRealtime() - started}ms")
            withContext(Dispatchers.Main) {
                if (TalktAuth.userId == owner) {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Talkt transcript", transcript.text))
                    }
                    settings.latestResult = transcript.text
                    settings.latestTranscriptionDetail = transcript.detail
                    notify(context, file, "Recording transcribed", transcript.text)
                }
                clip.close()
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: NoSpeechException) {
            clip.close()
            true
        } catch (error: Exception) {
            Log.w("Talkt", "pending transcription of ${file.name} failed: ${error.message}")
            if (TalktAuth.userId == owner) notify(context, file, "Recording kept", "${error.message ?: "Could not transcribe"}. Audio is saved on your phone.")
            false
        }
    }

    private fun notify(context: Context, file: File, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("talkt.recordings", "Saved recordings", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, CabinetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(5_100 + file.hashCode(), NotificationCompat.Builder(context, "talkt.recordings")
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(open).build())
    }
}
