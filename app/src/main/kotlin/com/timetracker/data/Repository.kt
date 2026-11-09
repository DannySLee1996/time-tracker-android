package com.timetracker.data

import android.content.Context
import com.timetracker.core.model.Tag
import com.timetracker.core.model.TimeEntry
import com.timetracker.core.model.WEAR_SNAPSHOT_PATH
import com.timetracker.core.model.WearSnapshot
import com.timetracker.core.sync.MergeChoice
import com.timetracker.core.sync.SyncClient
import com.timetracker.core.sync.SyncRequest
import com.timetracker.core.sync.mergeChoice
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class Repository(private val context: Context) {
    private val dao = TrackerDatabase.create(context).dao()
    private val store = SecureStore(context)
    private val client = SyncClient()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val tags = dao.observeTags().map { rows -> rows.filter { it.deletedAt == null }.map { it.toModel() } }
    val entries = dao.observeEntries().map { rows ->
        rows.filter { it.deletedAt == null }.map { it.toModel() }.sortedByDescending { it.startedAt }
    }
    val sessionEmail get() = store.email
    val serverUrl get() = store.serverUrl
    val signedIn get() = !store.token.isNullOrBlank()

    fun updateServerUrl(url: String) {
        store.serverUrl = url
    }

    suspend fun login(email: String, password: String, register: Boolean) {
        val result = if (register) client.register(store.serverUrl, email, password) else client.login(store.serverUrl, email, password)
        dao.clearTags()
        dao.clearEntries()
        store.lastSyncAt = null
        store.token = result.token
        store.email = result.user.email
        sync()
    }

    suspend fun signOut() {
        store.token = null
        store.email = null
        store.lastSyncAt = null
        dao.clearTags()
        dao.clearEntries()
    }

    suspend fun saveTag(tag: Tag) = rememberTag(tag)
    suspend fun saveEntry(entry: TimeEntry) = rememberEntry(entry)

    suspend fun startTimer(title: String, description: String?, tagIds: List<String>) {
        val now = Instant.now().toString()
        dao.openEntries().forEach { open ->
            rememberEntry(open.toModel().copy(endedAt = now))
        }
        rememberEntry(
            TimeEntry(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Untitled" },
                description = description,
                startedAt = now,
                endedAt = null,
                tagIds = tagIds,
                updatedAt = now,
                deviceId = store.deviceId(),
            ),
        )
    }

    suspend fun sync() {
        val token = store.token ?: return
        val dirtyTags = dao.dirtyTags()
        val dirtyEntries = dao.dirtyEntries()
        val sentTags = dirtyTags.associate { it.id to it.updatedAt }
        val sentEntries = dirtyEntries.associate { it.id to it.updatedAt }
        val response = client.sync(
            store.serverUrl,
            token,
            SyncRequest(
                deviceId = store.deviceId(),
                lastSyncAt = store.lastSyncAt,
                tags = dirtyTags.map { it.toModel() },
                timeEntries = dirtyEntries.map { it.toModel() },
            ),
        )
        response.tags.forEach { remote ->
            val local = dao.tag(remote.id)
            if (local?.dirty == true && sentTags[remote.id] != null && local.updatedAt != sentTags[remote.id]) return@forEach
            if (local?.dirty == true && sentTags[remote.id] == null && local.updatedAt > remote.updatedAt) return@forEach
            dao.upsertTag(remote.toEntity(dirty = false, baseUpdatedAt = remote.updatedAt))
        }
        response.timeEntries.forEach { remote ->
            val local = dao.entry(remote.id)
            if (local?.dirty == true && sentEntries[remote.id] != null && local.updatedAt != sentEntries[remote.id]) return@forEach
            if (local?.dirty == true && sentEntries[remote.id] == null && local.updatedAt > remote.updatedAt) return@forEach
            dao.upsertEntry(remote.toEntity(dirty = false, baseUpdatedAt = remote.updatedAt))
        }
        store.lastSyncAt = response.serverTime
        publishWearSnapshot()
    }

    suspend fun applyWearSnapshot(snapshot: WearSnapshot) {
        var changed = false
        snapshot.tags.forEach { incoming ->
            val local = dao.tag(incoming.id)
            val choice = mergeChoice(local?.updatedAt, local?.deviceId, incoming.updatedAt, incoming.baseUpdatedAt, incoming.deviceId)
            if (choice == MergeChoice.AcceptIncoming && local?.toModel() != incoming) {
                dao.upsertTag(incoming.toEntity(dirty = true, baseUpdatedAt = local?.baseUpdatedAt ?: local?.updatedAt))
                changed = true
            }
        }
        snapshot.timeEntries.forEach { incoming ->
            val local = dao.entry(incoming.id)
            val choice = mergeChoice(local?.updatedAt, local?.deviceId, incoming.updatedAt, incoming.baseUpdatedAt, incoming.deviceId)
            if (choice == MergeChoice.AcceptIncoming && local?.toModel() != incoming) {
                dao.upsertEntry(incoming.toEntity(dirty = true, baseUpdatedAt = local?.baseUpdatedAt ?: local?.updatedAt))
                changed = true
            }
        }
        if (changed) publishWearSnapshot()
    }

    suspend fun publishWearSnapshot() {
        try {
            val snapshot = WearSnapshot(
                tags = dao.allTags().map { it.toModel() },
                timeEntries = dao.allEntries().map { it.toModel() },
            )
            val request = PutDataMapRequest.create(WEAR_SNAPSHOT_PATH).apply {
                dataMap.putString("json", json.encodeToString(snapshot))
                dataMap.putLong("sentAt", System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
        } catch (_: Exception) {
            // The phone can track time without a paired watch.
        }
    }

    private suspend fun rememberTag(tag: Tag) {
        val existing = dao.tag(tag.id)
        val now = Instant.now().toString()
        val base = if (existing?.dirty == true) existing.baseUpdatedAt else existing?.updatedAt
        dao.upsertTag(tag.copy(updatedAt = now, deviceId = store.deviceId(), baseUpdatedAt = base).toEntity(dirty = true, baseUpdatedAt = base))
        publishWearSnapshot()
    }

    private suspend fun rememberEntry(entry: TimeEntry) {
        val existing = dao.entry(entry.id)
        val now = Instant.now().toString()
        val base = if (existing?.dirty == true) existing.baseUpdatedAt else existing?.updatedAt
        dao.upsertEntry(entry.copy(updatedAt = now, deviceId = store.deviceId(), baseUpdatedAt = base).toEntity(dirty = true, baseUpdatedAt = base))
        publishWearSnapshot()
    }
}
