package org.buddytools.talkt.wear

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.capture.RecordingStore
import org.buddytools.talkt.capture.SavedRecordingTranscriber
import org.buddytools.talkt.wearbridge.WearProtocol
import java.io.File
import java.util.concurrent.TimeUnit

class WatchTranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        if (!WearProtocol.recordingId.matches(id)) return Result.failure()
        val file = File(RecordingStore(applicationContext.filesDir, owner).directory, "$id.wav")
        if (!file.exists()) return Result.success()
        return try {
            if (TalktAuth.requireUserId() != owner) Result.retry()
            else if (SavedRecordingTranscriber.transcribe(applicationContext, file, owner)) Result.success()
            else Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, id: String, owner: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("watch-transcribe-${WearProtocol.ownerToken(owner)}-$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WatchTranscriptionWorker>()
                    .setInputData(workDataOf("id" to id, "owner" to owner))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get(10, TimeUnit.SECONDS)
        }
    }
}
