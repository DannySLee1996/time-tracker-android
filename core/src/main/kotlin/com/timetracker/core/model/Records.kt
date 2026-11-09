package com.timetracker.core.model

import kotlinx.serialization.Serializable

const val WEAR_SNAPSHOT_PATH = "/timetracker/snapshot"

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val updatedAt: String,
    val baseUpdatedAt: String? = null,
    val deletedAt: String? = null,
    val deviceId: String,
)

@Serializable
data class TimeEntry(
    val id: String,
    val title: String,
    val description: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val tagIds: List<String> = emptyList(),
    val updatedAt: String,
    val baseUpdatedAt: String? = null,
    val deletedAt: String? = null,
    val deviceId: String,
)

@Serializable
data class AuthUser(val id: String, val email: String, val admin: Boolean)

@Serializable
data class WearSnapshot(
    val tags: List<Tag> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
)
