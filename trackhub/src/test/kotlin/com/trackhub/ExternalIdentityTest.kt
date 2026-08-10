package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalIdentityTest {
    @Test
    fun providerNamespacesAreVersionIndependentAndBounded() {
        assertEquals("apphud", TrackHub.normalizedExternalProvider(" Apphud "))
        assertEquals("revenuecat", TrackHub.normalizedExternalProvider("RevenueCat"))
        assertEquals("custom:billing_v2", TrackHub.normalizedExternalProvider("custom:billing_v2"))
        assertNull(TrackHub.normalizedExternalProvider("custom:bad value"))
        assertNull(TrackHub.normalizedExternalProvider("custom:ä"))
        assertNull(TrackHub.normalizedExternalProvider("unknown"))
    }

    @Test
    fun externalIdentityWaitsForProductionInstallAcknowledgement() {
        assertFalse(TrackHub.shouldEnqueueExternalIdentity(false, false))
        assertTrue(TrackHub.shouldEnqueueExternalIdentity(true, false))
        assertTrue(TrackHub.shouldEnqueueExternalIdentity(false, true))
    }

    @Test
    fun legacyIdentityHeadLetsProductionInstallSelfHealFirst() {
        assertEquals(
            2,
            TrackHub.preferredPendingDeliveryIndex(
                listOf("external_identity", "event", "production_install"),
            ),
        )
        assertEquals(
            0,
            TrackHub.preferredPendingDeliveryIndex(
                listOf("external_identity", "event"),
            ),
        )
        assertEquals(
            0,
            TrackHub.preferredPendingDeliveryIndex(
                listOf("event", "production_install"),
            ),
        )
    }
}
