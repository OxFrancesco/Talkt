package org.buddytools.talkt.cloud

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.data.*
import java.util.concurrent.TimeUnit

class CloudBackupState(context: Context, owner: String) {
    val preferences = context.getSharedPreferences("cloud-backup-${accountStorageSuffix(owner)}", Context.MODE_PRIVATE)
    val enabled get() = preferences.getBoolean("enabled", false)
    fun excluded() = preferences.getStringSet("excluded", emptySet()).orEmpty().toSet()
    fun saved(endpoint: String) = preferences.getStringSet("saved-${accountStorageSuffix(endpoint)}", emptySet()).orEmpty().toSet()
    fun markSaved(endpoint: String, id: String) {
        check(preferences.edit().putStringSet("saved-${accountStorageSuffix(endpoint)}", saved(endpoint) + id).commit())
    }
    fun status(message: String) { preferences.edit().putString("status", message).apply() }
    fun enable(excluded: Set<String>) {
        check(preferences.edit().putStringSet("excluded", excluded).putBoolean("enabled", true).remove("status").commit())
    }
    fun disable() { check(preferences.edit().putBoolean("enabled", false).remove("status").commit()) }
}

class CloudBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = serial.withLock {
        val owner = inputData.getString("owner") ?: return@withLock Result.failure()
        val state = CloudBackupState(applicationContext, owner)
        if (!state.enabled) return@withLock Result.success()
        try {
            if (TalktAuth.requireUserId() != owner) return@withLock Result.success()
            val settings = TalktSettings(applicationContext)
            val endpoint = settings.apiUrl
            val api = TalktApi(settings, endpoint)
            val saved = state.saved(endpoint)
            val excluded = state.excluded()
            state.status("Backing up")
            for (entry in TranscriptStore(applicationContext, owner).list()) {
                if (isStopped || !state.enabled || TalktAuth.userId != owner) return@withLock Result.success()
                if (entry.id in saved || entry.id in excluded) continue
                val complete = try { api.saveTranscript(owner, entry) }
                catch (error: TalktApiException) {
                    if (error.statusCode == 410) true else throw error
                }
                if (!complete) {
                    state.status("Backup pending. Will retry when connected.")
                    return@withLock Result.retry()
                }
                state.markSaved(endpoint, entry.id)
            }
            if (state.enabled && TalktAuth.userId == owner) state.status("Backup up to date")
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (!state.enabled || TalktAuth.userId != owner) return@withLock Result.success()
            val permanent = error is TalktApiException && error.statusCode in setOf(400, 403, 409, 413)
            state.status(if (permanent) "Backup failed: ${error.message}" else "Backup pending. Open Talkt to sign in or retry.")
            if (permanent) Result.failure() else Result.retry()
        }
    }

    companion object {
        private val serial = Mutex()
        fun enqueue(context: Context, owner: String?) {
            if (owner == null || !CloudBackupState(context, owner).enabled) return
            val name = "cloud-backup-${accountStorageSuffix(owner)}"
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val data = workDataOf("owner" to owner)
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudBackupWorker>().setInputData(data).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(name).build())
            manager.enqueueUniquePeriodicWork("$name-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudBackupWorker>(15, TimeUnit.MINUTES)
                    .setInputData(data).setConstraints(constraints).addTag(name).build())
        }
        fun disable(context: Context, owner: String) {
            CloudBackupState(context, owner).disable()
            WorkManager.getInstance(context).cancelAllWorkByTag("cloud-backup-${accountStorageSuffix(owner)}")
        }
    }
}
