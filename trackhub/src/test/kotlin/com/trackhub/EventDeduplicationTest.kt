package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDeduplicationTest {
    @Test
    fun sharedContractVectorIsStable() {
        assertEquals(
            "dedup1-9068017e11119b7a3c99163c1cb825e87ecda0542a4405cb526d506b941eb579",
            TrackHub.deduplicatedClientEventId(
                installUid = "11111111-2222-4333-8444-555555555555",
                eventName = " tutorial_done ",
                deduplicationId = "order-42",
            ),
        )
    }

    @Test
    fun identifiersAreTrimmedBoundedAndInstallScoped() {
        val first = TrackHub.deduplicatedClientEventId("install-a", "level_done", " level-10 ")
        assertEquals(
            first,
            TrackHub.deduplicatedClientEventId("install-a", "level_done", "level-10"),
        )
        assertNotEquals(
            first,
            TrackHub.deduplicatedClientEventId("install-b", "level_done", "level-10"),
        )
        assertNull(TrackHub.deduplicatedClientEventId("install-a", "level_done", "   "))
        assertNull(TrackHub.deduplicatedClientEventId("install-a", "level_done", "x".repeat(257)))
    }
}
