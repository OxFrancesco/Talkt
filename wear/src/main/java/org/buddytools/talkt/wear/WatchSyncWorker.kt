package org.buddytools.talkt.wear

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.buddytools.talkt.wearbridge.WearProtocol
import java.io.File
import java.util.concurrent.TimeUnit

class WatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchStore(this).recover()
        WatchSyncWorker.enqueue(this)
    }
}

class WatchDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path == WearProtocol.ACCOUNT || it.dataItem.uri.path?.startsWith(WearProtocol.RECEIPT) == true }) {
            WatchSyncWorker.enqueue(this)
        }
    }
}

class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = WatchStore(applicationContext)
        val client = Wearable.getDataClient(applicationContext)
        try {
            val localNode = Wearable.getNodeClient(applicationContext).localNode.await().id
            val items = client.dataItems.await()
            try {
                items.filter { it.uri.path == WearProtocol.ACCOUNT && it.uri.host != localNode }
                    .firstOrNull { store.phone.isBlank() || it.uri.host == store.phone }?.let {
                        store.account(it.uri.host!!, DataMapItem.fromDataItem(it).dataMap.getString("owner").orEmpty())
                    }
                items.filter { it.uri.path?.startsWith(WearProtocol.RECEIPT) == true }.forEach { item ->
                    val map = DataMapItem.fromDataItem(item).dataMap
                    val id = map.getString("id").orEmpty()
                    if (!WearProtocol.recordingId.matches(id) || map.getString("watch") != localNode) return@forEach
                    val audio = File(store.directory, "$id.wav")
                    val metadata = File(store.directory, "$id.json")
                    if (metadata.exists()) {
                        val binding = store.binding(id)
                        if (binding.optString("phone") != item.uri.host || binding.optString("owner") != map.getString("owner")) return@forEach
                        check(!audio.exists() || audio.delete())
                        check(metadata.delete())
                    }
                    val recordingUri = Uri.Builder().scheme("wear").authority(localNode).path(WearProtocol.RECORDING + id).build()
                    client.deleteDataItems(recordingUri).await()
                    client.deleteDataItems(item.uri).await()
                }
            } finally { items.release() }

            store.bindUnassigned()
            for (file in store.pending()) {
                val binding = store.binding(file.nameWithoutExtension)
                val owner = binding.optString("owner")
                if (owner.isBlank()) continue
                WearProtocol.validateWav(file)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    val request = PutDataMapRequest.create(WearProtocol.RECORDING + file.nameWithoutExtension).apply {
                        dataMap.putString("id", file.nameWithoutExtension)
                        dataMap.putString("owner", owner)
                        dataMap.putString("phone", binding.getString("phone"))
                        dataMap.putAsset("audio", Asset.createFromFd(descriptor))
                    }.asPutDataRequest().setUrgent()
                    client.putDataItem(request).await()
                }
            }
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("Talkt", "watch sync deferred", error)
            return Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("watch-transfer", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchSyncWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        suspend fun requestPhone(context: Context) {
            Wearable.getDataClient(context).putDataItem(PutDataMapRequest.create(WearProtocol.REQUEST).apply {
                dataMap.putLong("requestedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()).await()
            enqueue(context)
        }
    }
}
