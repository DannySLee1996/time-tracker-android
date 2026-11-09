package com.timetracker.wear

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.timetracker.core.model.Tag
import com.timetracker.core.model.TimeEntry
import com.timetracker.core.model.WEAR_SNAPSHOT_PATH
import com.timetracker.core.model.WearSnapshot
import com.timetracker.core.sync.MergeChoice
import com.timetracker.core.sync.mergeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

@Entity(tableName = "tags")
data class WearTagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val updatedAt: String,
    val baseUpdatedAt: String?,
    val deletedAt: String?,
    val deviceId: String,
)

@Entity(tableName = "time_entries")
data class WearEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val startedAt: String,
    val endedAt: String?,
    val tagIds: String,
    val updatedAt: String,
    val baseUpdatedAt: String?,
    val deletedAt: String?,
    val deviceId: String,
)

private fun WearTagEntity.toModel() = Tag(id, name, description, color, updatedAt, baseUpdatedAt, deletedAt, deviceId)
private fun Tag.toEntity() = WearTagEntity(id, name, description, color, updatedAt, baseUpdatedAt, deletedAt, deviceId)
private fun WearEntryEntity.toModel() = TimeEntry(
    id, title, description, startedAt, endedAt,
    if (tagIds.isBlank()) emptyList() else tagIds.split(","),
    updatedAt, baseUpdatedAt, deletedAt, deviceId,
)
private fun TimeEntry.toEntity() = WearEntryEntity(
    id, title, description, startedAt, endedAt, tagIds.joinToString(","), updatedAt, baseUpdatedAt, deletedAt, deviceId,
)

@Dao
interface WearDao {
    @Query("SELECT * FROM time_entries")
    fun observeEntries(): Flow<List<WearEntryEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: String): WearTagEntity?

    @Query("SELECT * FROM time_entries WHERE id = :id")
    suspend fun entry(id: String): WearEntryEntity?

    @Query("SELECT * FROM tags")
    suspend fun allTags(): List<WearTagEntity>

    @Query("SELECT * FROM time_entries")
    suspend fun allEntries(): List<WearEntryEntity>

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL AND deletedAt IS NULL")
    suspend fun openEntries(): List<WearEntryEntity>

    @Upsert
    suspend fun upsertTag(tag: WearTagEntity)

    @Upsert
    suspend fun upsertEntry(entry: WearEntryEntity)
}

@Database(entities = [WearTagEntity::class, WearEntryEntity::class], version = 1, exportSchema = false)
abstract class WearDatabase : RoomDatabase() {
    abstract fun dao(): WearDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, WearDatabase::class.java, "timetracker-wear.db").build()
    }
}

class WearRepository(private val context: Context) {
    private val dao = WearDatabase.create(context).dao()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val deviceId = "watch"
    val entries = dao.observeEntries().map { rows ->
        rows.filter { it.deletedAt == null }.map { it.toModel() }.sortedByDescending { it.startedAt }
    }

    suspend fun start() {
        val now = Instant.now().toString()
        val last = dao.allEntries().maxByOrNull { it.startedAt }
        dao.openEntries().forEach { open -> save(open.toModel().copy(endedAt = now)) }
        save(
            TimeEntry(
                id = UUID.randomUUID().toString(),
                title = last?.title ?: "Timer",
                description = last?.description,
                startedAt = now,
                tagIds = last?.toModel()?.tagIds ?: emptyList(),
                updatedAt = now,
                deviceId = deviceId,
            ),
        )
    }

    suspend fun stop(entry: TimeEntry) {
        save(entry.copy(endedAt = Instant.now().toString()))
    }

    suspend fun apply(snapshot: WearSnapshot) {
        var changed = false
        snapshot.tags.forEach { incoming ->
            val local = dao.tag(incoming.id)
            if (mergeChoice(local?.updatedAt, local?.deviceId, incoming.updatedAt, incoming.baseUpdatedAt, incoming.deviceId) == MergeChoice.AcceptIncoming && local?.toModel() != incoming) {
                dao.upsertTag(incoming.toEntity())
                changed = true
            }
        }
        snapshot.timeEntries.forEach { incoming ->
            val local = dao.entry(incoming.id)
            if (mergeChoice(local?.updatedAt, local?.deviceId, incoming.updatedAt, incoming.baseUpdatedAt, incoming.deviceId) == MergeChoice.AcceptIncoming && local?.toModel() != incoming) {
                dao.upsertEntry(incoming.toEntity())
                changed = true
            }
        }
        if (changed) publish()
    }

    private suspend fun save(entry: TimeEntry) {
        val now = Instant.now().toString()
        val existing = dao.entry(entry.id)
        dao.upsertEntry(entry.copy(updatedAt = now, deviceId = deviceId, baseUpdatedAt = existing?.updatedAt).toEntity())
        publish()
    }

    private suspend fun publish() {
        try {
            val snapshot = WearSnapshot(dao.allTags().map { it.toModel() }, dao.allEntries().map { it.toModel() })
            val request = PutDataMapRequest.create(WEAR_SNAPSHOT_PATH).apply {
                dataMap.putString("json", json.encodeToString(snapshot))
                dataMap.putLong("sentAt", System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
        } catch (_: Exception) {
            // The watch keeps the timer if the phone link is down.
        }
    }
}
