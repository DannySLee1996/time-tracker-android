package com.timetracker.core.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LwwTest {
    @Test
    fun newRecordIsAccepted() {
        assertEquals(
            MergeChoice.AcceptIncoming,
            mergeChoice(null, null, "2026-11-08T12:00:00Z", null, "phone"),
        )
    }

    @Test
    fun fastForwardAcceptsALaggingClock() {
        val base = "2026-11-08T12:00:00Z"
        val choice = mergeChoice(base, "phone", "2026-11-08T10:00:00Z", base, "phone")
        assertEquals(MergeChoice.AcceptIncoming, choice)
        assertTrue(Instant.parse(acceptedTimestamp(base, "2026-11-08T10:00:00Z")).isAfter(Instant.parse(base)))
    }

    @Test
    fun concurrentEditKeepsTheLaterTimestamp() {
        val existing = "2026-11-08T12:00:00Z"
        assertEquals(
            MergeChoice.KeepExisting,
            mergeChoice(existing, "phone", "2026-11-08T11:00:00Z", "2026-11-07T12:00:00Z", "web"),
        )
        assertEquals(
            MergeChoice.AcceptIncoming,
            mergeChoice(existing, "phone", "2026-11-08T13:00:00Z", "2026-11-07T12:00:00Z", "web"),
        )
    }

    @Test
    fun equalTimestampsBreakTheTieOnDeviceId() {
        val stamp = "2026-11-08T12:00:00Z"
        assertEquals(MergeChoice.AcceptIncoming, mergeChoice(stamp, "a", stamp, null, "b"))
        assertEquals(MergeChoice.KeepExisting, mergeChoice(stamp, "b", stamp, null, "a"))
    }
}
