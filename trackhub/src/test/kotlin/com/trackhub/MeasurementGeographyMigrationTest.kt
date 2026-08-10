package com.trackhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementGeographyMigrationTest {
    @Test
    fun onlyTheRetired302RefreshReportIsRemoved() {
        assertTrue(TrackHub.isRetiredMeasurementGeographyReport("install_geo_refresh", null))
        assertTrue(TrackHub.isRetiredMeasurementGeographyReport(null, "install_geo_refresh_v1"))
        assertFalse(TrackHub.isRetiredMeasurementGeographyReport("production_install", "install"))
        assertFalse(TrackHub.isRetiredMeasurementGeographyReport("sdk_track", null))
    }
}
