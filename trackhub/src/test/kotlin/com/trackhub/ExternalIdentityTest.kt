package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
