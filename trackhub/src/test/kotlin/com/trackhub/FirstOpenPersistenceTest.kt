package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstOpenPersistenceTest {
    @Test
    fun storedTimestampIsUsedWithoutRewritingIt() {
        var writes = 0
        val result = TrackHub.resolveFirstOpenAtValue(
            storedMs = 1_800_000_000_000L,
            volatileMs = 1_700_000_000_000L,
            nowMs = 1_900_000_000_000L,
            persist = {
                writes += 1
                true
            },
        )

        assertTrue(result.durable)
        assertEquals(1_800_000_000_000L, result.valueMs)
        assertEquals(0, writes)
    }

    @Test
    fun generatedTimestampIsReturnedOnlyWithPersistenceResult() {
        var persisted: Long? = null
        val result = TrackHub.resolveFirstOpenAtValue(
            storedMs = 0L,
            volatileMs = null,
            nowMs = 1_800_000_000_123L,
            persist = {
                persisted = it
                true
            },
        )

        assertTrue(result.durable)
        assertEquals(1_800_000_000_123L, result.valueMs)
        assertEquals(result.valueMs, persisted)
    }

    @Test
    fun failedPersistenceKeepsOneStableProcessValue() {
        val first = TrackHub.resolveFirstOpenAtValue(
            storedMs = 0L,
            volatileMs = null,
            nowMs = 1_800_000_000_000L,
            persist = { false },
        )
        val second = TrackHub.resolveFirstOpenAtValue(
            storedMs = 0L,
            volatileMs = first.valueMs,
            nowMs = 1_900_000_000_000L,
            persist = { false },
        )

        assertFalse(first.durable)
        assertFalse(second.durable)
        assertEquals(first.valueMs, second.valueMs)
    }
}
