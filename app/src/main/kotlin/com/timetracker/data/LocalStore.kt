package com.timetracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.timetracker.core.model.Tag
import com.timetracker.core.model.TimeEntry
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val updatedAt: String,
    val baseUpdatedAt: String?,
    val deletedAt: String?,
    val deviceId: String,
    val dirty: Boolean,
)

@Entity(tableName = "time_entries")
data class EntryEntity(
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
    val dirty: Boolean,
)

fun TagEntity.toModel() = Tag(id, name, description, color, updatedAt, baseUpdatedAt, deletedAt, deviceId)

fun Tag.toEntity(dirty: Boolean, baseUpdatedAt: String? = this.baseUpdatedAt) = TagEntity(
    id, name, description, color, updatedAt, baseUpdatedAt, deletedAt, deviceId, dirty,
)

fun EntryEntity.toModel() = TimeEntry(
    id, title, description, startedAt, endedAt, if (tagIds.isBlank()) emptyList() else tagIds.split(","),
    updatedAt, baseUpdatedAt, deletedAt, deviceId,
)

fun TimeEntry.toEntity(dirty: Boolean, baseUpdatedAt: String? = this.baseUpdatedAt) = EntryEntity(
    id, title, description, startedAt, endedAt, tagIds.joinToString(","), updatedAt, baseUpdatedAt, deletedAt, deviceId, dirty,
)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM tags")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM time_entries")
    fun observeEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM tags WHERE dirty = 1")
    suspend fun dirtyTags(): List<TagEntity>

    @Query("SELECT * FROM time_entries WHERE dirty = 1")
    suspend fun dirtyEntries(): List<EntryEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: String): TagEntity?

    @Query("SELECT * FROM time_entries WHERE id = :id")
    suspend fun entry(id: String): EntryEntity?

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL AND deletedAt IS NULL")
    suspend fun openEntries(): List<EntryEntity>

    @Query("SELECT * FROM tags")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM time_entries")
    suspend fun allEntries(): List<EntryEntity>

    @Upsert
    suspend fun upsertTag(tag: TagEntity)

    @Upsert
    suspend fun upsertEntry(entry: EntryEntity)

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM time_entries")
    suspend fun clearEntries()
}

@Database(entities = [TagEntity::class, EntryEntity::class], version = 1, exportSchema = false)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, TrackerDatabase::class.java, "timetracker.db").build()
    }
}

class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "timetracker",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString("token", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("token") else putString("token", value)
            }.apply()
        }

    var email: String?
        get() = prefs.getString("email", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("email") else putString("email", value)
            }.apply()
        }

    var serverUrl: String
        get() = prefs.getString("url", "http://10.0.2.2:3000/graphql") ?: "http://10.0.2.2:3000/graphql"
        set(value) = prefs.edit().putString("url", value).apply()

    var lastSyncAt: String?
        get() = prefs.getString("lastSyncAt", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("lastSyncAt") else putString("lastSyncAt", value)
            }.apply()
        }

    fun deviceId(): String {
        val existing = prefs.getString("deviceId", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("deviceId", created).apply()
        return created
    }
}
