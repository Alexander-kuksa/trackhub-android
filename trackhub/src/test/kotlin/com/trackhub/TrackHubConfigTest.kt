package com.trackhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHubConfigTest {
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
