package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementGeographyTest {
    @Test
    fun installAckGeographyIsValidatedAndInstallScoped() {
        val installUid = "11111111-2222-4333-8444-555555555555"
        assertEquals(
            TrackHub.MeasurementGeographyAck(version = 1, country = "DE", eea = true),
            TrackHub.parseMeasurementGeographyAck(
                countryValue = " de ",
                eeaValue = true,
                geoAckVersionValue = 1,
                responseInstallUid = installUid,
                expectedInstallUid = installUid,
            ),
        )
        assertNull(
            TrackHub.parseMeasurementGeographyAck(
                countryValue = "USA",
                eeaValue = 1,
                geoAckVersionValue = true,
                responseInstallUid = null,
                expectedInstallUid = installUid,
            ),
        )
        assertNull(
            TrackHub.parseMeasurementGeographyAck(
                countryValue = "US",
                eeaValue = false,
                geoAckVersionValue = 1,
                responseInstallUid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                expectedInstallUid = installUid,
            ),
        )
    }

    @Test
    fun cachedNonEeaCannotNarrowAProtectiveHostSignal() {
        assertEquals(true, TrackHub.resolvedMeasurementEea(host = true, cached = false))
        assertEquals(true, TrackHub.resolvedMeasurementEea(host = false, cached = true))
        assertEquals(false, TrackHub.resolvedMeasurementEea(host = null, cached = false))
        assertNull(TrackHub.resolvedMeasurementEea(host = null, cached = null))
        assertEquals(
            TrackHub.MeasurementGeographySignal(country = "DE", eea = true),
            TrackHub.resolvedMeasurementGeography(
                hostCountry = "US",
                hostEea = true,
                cachedCountry = "de",
                cachedEea = false,
            ),
        )
    }

    @Test
    fun upgradeRefreshIsOneBoundedDurableJob() {
        assertTrue(TrackHub.shouldRefreshMeasurementGeography(
            installAlreadySent = true,
            hasCredential = true,
            hasCachedGeo = false,
            refreshTerminal = false,
            refreshPending = false,
        ))
        assertFalse(TrackHub.shouldRefreshMeasurementGeography(
            installAlreadySent = true,
            hasCredential = true,
            hasCachedGeo = false,
            refreshTerminal = false,
            refreshPending = true,
        ))
        assertFalse(TrackHub.shouldRefreshMeasurementGeography(
            installAlreadySent = true,
            hasCredential = true,
            hasCachedGeo = false,
            refreshTerminal = true,
            refreshPending = false,
        ))
        assertFalse(TrackHub.shouldTerminateMeasurementGeoRefresh(11))
        assertTrue(TrackHub.shouldTerminateMeasurementGeoRefresh(12))
        assertTrue(TrackHub.shouldAwaitMeasurementGeoAck(true, null))
        assertFalse(TrackHub.shouldAwaitMeasurementGeoAck(true, 1))
        assertFalse(TrackHub.shouldAwaitMeasurementGeoAck(false, null))
    }
}
