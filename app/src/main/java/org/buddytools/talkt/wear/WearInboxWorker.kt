package org.buddytools.talkt.wear

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clerk.api.Clerk
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.capture.RecordingStore
import org.buddytools.talkt.wearbridge.WearProtocol
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class WearInboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val client = Wearable.getDataClient(applicationContext)
        try {
            if (withTimeoutOrNull(15_000) { Clerk.isInitialized.first { it } } == null) return Result.retry()
            val owner = TalktAuth.userId
            val token = owner?.let(WearProtocol::ownerToken).orEmpty()
            client.putDataItem(PutDataMapRequest.create(WearProtocol.ACCOUNT).apply {
                dataMap.putString("owner", token)
            }.asPutDataRequest().setUrgent()).await()
            if (owner == null) return Result.success()
            val node = Wearable.getNodeClient(applicationContext).localNode.await().id
            val items = client.dataItems.await()
            var retry = false
            try {
                for (item in items) {
                    if (item.uri.path?.startsWith(WearProtocol.RECORDING) != true || item.uri.host == node) continue
                    val map = DataMapItem.fromDataItem(item).dataMap
                    val id = map.getString("id").orEmpty()
                    if (!WearProtocol.recordingId.matches(id) || item.uri.path != WearProtocol.RECORDING + id) continue
                    if (map.getString("phone") != node || map.getString("owner") != token) continue
                    TalktAuth.ensureUser(owner)
                    val watch = item.uri.host ?: continue
                    val receiptKey = WearProtocol.ownerToken("$watch/$id")
                    val receipts = applicationContext.getSharedPreferences("wear-receipts", Context.MODE_PRIVATE)
                    val directory = RecordingStore(applicationContext.filesDir, owner).directory.apply { mkdirs() }
                    val audio = File(directory, "$id.wav")
                    // Keep staged bytes until the receipt and final file can survive separate process deaths.
                    val staging = File(applicationContext.filesDir, "wear-incoming").apply { mkdirs() }
                    val temporary = File(staging, "$receiptKey.tmp")
                    if (!receipts.getBoolean(receiptKey, false)) {
                        val asset = map.getAsset("audio") ?: continue
                        // Incoming files stay outside RecordingStore until fully validated, so orphan recovery cannot adopt a partial transfer.
                        try {
                            val response = client.getFdForAsset(asset).await()
                            response.inputStream.use { input ->
                                checkNotNull(input) { "Watch audio is unavailable" }
                                FileOutputStream(temporary).use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= WearProtocol.MAX_AUDIO_BYTES) { "Watch recording is too large" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.fd.sync()
                                }
                            }
                            WearProtocol.validateWav(temporary)
                            TalktAuth.ensureUser(owner)
                            check(receipts.edit().putBoolean(receiptKey, true).commit()) { "Could not save receipt" }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("Talkt", "watch recording deferred: $id", error)
                            retry = true
                            continue
                        } finally {
                            if (!receipts.getBoolean(receiptKey, false)) temporary.delete()
                        }
                    }
                    if (temporary.exists()) {
                        TalktAuth.ensureUser(owner)
                        check(temporary.renameTo(audio)) { "Could not save watch audio" }
                        Log.i("Talkt", "watch recording saved: $id")
                    }
                    if (audio.exists()) WatchTranscriptionWorker.enqueue(applicationContext, id, owner)
                    client.putDataItem(PutDataMapRequest.create(WearProtocol.RECEIPT + receiptKey).apply {
                        dataMap.putString("id", id)
                        dataMap.putString("owner", token)
                        dataMap.putString("watch", watch)
                    }.asPutDataRequest().setUrgent()).await()
                }
            } finally { items.release() }
            return if (retry) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("Talkt", "phone wear sync deferred", error)
            return Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("wear-inbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WearInboxWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
