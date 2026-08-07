package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockSkewTest {
    @Test
    fun `accepts only a sane explicit clock skew response`() {
        val local = 1_800_000_000_000L
        assertEquals(
            12_345L,
            TrackHub.serverClockOffset(
                "clock_skew",
                1_800_000_012_345L,
                local,
            ),
        )
        assertNull(TrackHub.serverClockOffset("unauthorized", 1_800_000_012_345L, local))
        assertNull(TrackHub.serverClockOffset("clock_skew", 1L, local))
    }
}
