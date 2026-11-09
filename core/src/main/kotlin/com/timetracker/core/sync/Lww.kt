package com.timetracker.core.sync

import java.time.Instant

enum class MergeChoice {
    AcceptIncoming,
    KeepExisting,
}

fun mergeChoice(
    existingUpdatedAt: String?,
    existingDeviceId: String?,
    incomingUpdatedAt: String,
    incomingBaseUpdatedAt: String?,
    incomingDeviceId: String,
): MergeChoice {
    if (existingUpdatedAt == null) return MergeChoice.AcceptIncoming
    if (incomingBaseUpdatedAt != null && sameInstant(incomingBaseUpdatedAt, existingUpdatedAt)) {
        return MergeChoice.AcceptIncoming
    }
    val comparison = instant(incomingUpdatedAt).compareTo(instant(existingUpdatedAt))
    return when {
        comparison > 0 -> MergeChoice.AcceptIncoming
        comparison < 0 -> MergeChoice.KeepExisting
        incomingDeviceId >= (existingDeviceId ?: "") -> MergeChoice.AcceptIncoming
        else -> MergeChoice.KeepExisting
    }
}

fun acceptedTimestamp(existingUpdatedAt: String?, incomingUpdatedAt: String): String {
    if (existingUpdatedAt == null) return incomingUpdatedAt
    val existing = instant(existingUpdatedAt)
    val incoming = instant(incomingUpdatedAt)
    return if (incoming.isAfter(existing)) incomingUpdatedAt else existing.plusMillis(1).toString()
}

private fun sameInstant(left: String, right: String): Boolean = instant(left) == instant(right)

private fun instant(value: String): Instant = Instant.parse(value)
