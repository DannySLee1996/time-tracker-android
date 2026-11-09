package com.timetracker.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.timetracker.TrackerApplication
import com.timetracker.core.model.WEAR_SNAPSHOT_PATH
import com.timetracker.core.model.WearSnapshot
import com.timetracker.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class PhoneWearService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(events: DataEventBuffer) {
        val payloads = events.mapNotNull { event ->
            val item = event.dataItem
            if (item.uri.path != WEAR_SNAPSHOT_PATH) return@mapNotNull null
            DataMapItem.fromDataItem(item).dataMap.getString("json")
        }
        events.release()
        payloads.forEach { payload ->
            val snapshot = json.decodeFromString<WearSnapshot>(payload)
            scope.launch {
                (application as TrackerApplication).repository.applyWearSnapshot(snapshot)
                SyncWorker.enqueue(this@PhoneWearService)
            }
        }
    }
}
