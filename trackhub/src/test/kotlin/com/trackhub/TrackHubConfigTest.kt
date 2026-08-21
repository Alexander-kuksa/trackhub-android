package com.trackhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHubConfigTest {
    @Test
    fun advertisingIdHostCapabilityDefaultsOnForRemoteOwnerControl() {
        assertTrue(TrackHubConfig("invalid-but-redacted-sdk-key").collectAdvertisingId)
        assertFalse(TrackHub.shouldCollectAdvertisingId(true, false))
        assertTrue(TrackHub.shouldCollectAdvertisingId(true, true))
        assertFalse(TrackHub.shouldCollectAdvertisingId(false, true))
    }

    @Test
    fun remoteAdvertisingIdConfigIsStrictAndFailsClosed() {
        assertEquals(
            true,
            TrackHub.parseRemoteAdvertisingIdCollectionEnabled(
                "{\"androidAdvertisingIdCollectionEnabled\":true}",
            ),
        )
        assertEquals(
            false,
            TrackHub.parseRemoteAdvertisingIdCollectionEnabled(
                "{\"androidAdvertisingIdCollectionEnabled\":false}",
            ),
        )
        assertNull(TrackHub.parseRemoteAdvertisingIdCollectionEnabled("{}"))
        assertNull(
            TrackHub.parseRemoteAdvertisingIdCollectionEnabled(
                "{\"androidAdvertisingIdCollectionEnabled\":\"true\"}",
            ),
        )
        assertNull(TrackHub.parseRemoteAdvertisingIdCollectionEnabled("not-json"))
    }

    @Test
    fun googleAdsConsentIsOptionalAndDoesNotInferEitherSignal() {
        val consent = TrackHubGoogleAdsConsent()

        assertEquals(TrackHubConsentStatus.UNKNOWN, consent.adUserData)
        assertEquals(TrackHubConsentStatus.UNKNOWN, consent.adPersonalization)
        assertNull(consent.isEea)
    }

    @Test
    fun toStringRedactsSdkKeyAndFirebaseIdentifier() {
        val sdkKey = "thcfg_v1_super-secret-material"
        val firebaseId = "firebase-install-identifier"
        val testToken = "test-lab-token-that-must-not-be-logged"
        val rendered = TrackHubConfig(
            sdkKey = sdkKey,
            environment = TrackHubEnvironment.TestLab(testToken),
            firebaseAppInstanceId = firebaseId,
        ).toString()

        assertFalse(rendered.contains(sdkKey))
        assertFalse(rendered.contains(firebaseId))
        assertFalse(rendered.contains(testToken))
        assertTrue(rendered.contains("sdkKey=<redacted>"))
        assertTrue(rendered.contains("testLab(<redacted>)"))
    }
}
