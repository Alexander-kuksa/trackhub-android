package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeferredMatchTokenTest {
    @Test
    fun readsOpaqueTokenWithoutDroppingExistingReferrerValues() {
        assertEquals(
            "click_123",
            TrackHub.deferredMatchToken(
                "utm_source=email&trackhub_match_token=click_123&utm_campaign=summer",
            ),
        )
    }

    @Test
    fun decodesAndBoundsTheToken() {
        assertEquals("click token", TrackHub.deferredMatchToken("trackhub_match_token=click%20token"))
        assertNull(TrackHub.deferredMatchToken("utm_source=email"))
        assertNull(TrackHub.deferredMatchToken("trackhub_match_token=${"x".repeat(129)}"))
    }
}
