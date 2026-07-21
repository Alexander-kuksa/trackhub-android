package com.trackhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointValidationTest {
    @Test
    fun acceptsHttpsAndExactLoopbackHttp() {
        assertTrue(TrackHub.isAllowedEndpoint("https://postbacks.example.com"))
        assertTrue(TrackHub.isAllowedEndpoint("https://postbacks.example.com/base"))
        assertTrue(TrackHub.isAllowedEndpoint("http://localhost:3000"))
        assertTrue(TrackHub.isAllowedEndpoint("http://127.0.0.1:3000"))
        assertTrue(TrackHub.isAllowedEndpoint("http://[::1]:3000"))
    }

    @Test
    fun rejectsPrefixConfusionAndNonHttpSchemes() {
        assertFalse(TrackHub.isAllowedEndpoint("http://localhost.evil.example"))
        assertFalse(TrackHub.isAllowedEndpoint("http://127.0.0.1.evil.example"))
        assertFalse(TrackHub.isAllowedEndpoint("http://localhost@evil.example"))
        assertFalse(TrackHub.isAllowedEndpoint("http://postbacks.example.com"))
        assertFalse(TrackHub.isAllowedEndpoint("ftp://localhost/resource"))
        assertFalse(TrackHub.isAllowedEndpoint(" https://postbacks.example.com"))
        assertFalse(TrackHub.isAllowedEndpoint("https://postbacks.example.com?token=bad"))
    }

    @Test
    fun acceptsOnlyExplicitIsoCountryCodes() {
        assertEquals("DE", TrackHub.normalizedCountryCode(" de "))
        assertNull(TrackHub.normalizedCountryCode("XX"))
        assertNull(TrackHub.normalizedCountryCode("Europe"))
    }
}
