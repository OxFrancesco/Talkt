package org.buddytools.talkt.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import org.buddytools.talkt.wearbridge.WearProtocol

class WearRecordingService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path == WearProtocol.REQUEST || it.dataItem.uri.path?.startsWith(WearProtocol.RECORDING) == true }) {
            WearInboxWorker.enqueue(this)
        }
    }
}
