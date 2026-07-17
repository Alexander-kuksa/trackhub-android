package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueNamespaceTest {
    @Test
    fun productionAndTestLabQueuesCannotMix() {
        val a = TrackHub.offlineQueueNamespace("test-run-token-with-enough-entropy-a")
        val b = TrackHub.offlineQueueNamespace("test-run-token-with-enough-entropy-b")
        assertEquals("production", TrackHub.offlineQueueNamespace(null))
        assertTrue(a.startsWith("test-"))
        assertNotEquals(a, b)
        assertFalse(a.contains("test-run-token"))
    }
}
