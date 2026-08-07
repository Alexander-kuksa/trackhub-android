package com.trackhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCredentialTest {
    @Test
    fun acceptsOnlyCurrentVersionedCredentialShape() {
        assertTrue(TrackHub.isValidInstallCredential("thic_v1_" + "A".repeat(43)))
        assertFalse(TrackHub.isValidInstallCredential("thic_v1_short"))
        assertFalse(TrackHub.isValidInstallCredential("thic_v2_" + "A".repeat(43)))
        assertFalse(TrackHub.isValidInstallCredential("thic_v1_" + "+".repeat(43)))
    }

    @Test
    fun upgradeBootstrapIsBoundedAndStopsAfterCredentialReceipt() {
        assertFalse(TrackHub.shouldReportInstallForCredential(true, false, 1_000, 1_100, 200))
        assertTrue(TrackHub.shouldReportInstallForCredential(true, false, 1_000, 1_201, 200))
        assertFalse(TrackHub.shouldReportInstallForCredential(true, true, 0, 10_000, 200))
        assertTrue(TrackHub.shouldReportInstallForCredential(false, false, 0, 10_000, 200))
    }
}
