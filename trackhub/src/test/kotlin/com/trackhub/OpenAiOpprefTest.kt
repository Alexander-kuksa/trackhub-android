package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiOpprefTest {
    @Test
    fun preservesAndAcceptsBoundedReference() {
        assertEquals("  chatgpt-click-123  ", TrackHub.normalizedOpenAiOppref("  chatgpt-click-123  "))
        assertEquals("x".repeat(1024), TrackHub.normalizedOpenAiOppref("x".repeat(1024)))
    }

    @Test
    fun rejectsMissingEmptyAndOversizedReference() {
        assertNull(TrackHub.normalizedOpenAiOppref(null))
        assertNull(TrackHub.normalizedOpenAiOppref(""))
        assertNull(TrackHub.normalizedOpenAiOppref("x".repeat(1025)))
    }

    @Test
    fun extractsOpaqueReferenceWithoutUrlDecodingOrTrimming() {
        assertEquals(
            "raw%2Breference+value",
            TrackHub.rawOpenAiOpprefFromEncodedQuery(
                "foo=1&oppref=raw%2Breference+value&bar=2",
            ),
        )
        assertEquals(
            "%20OpenAI-Click-123%20",
            TrackHub.rawOpenAiOpprefFromEncodedQuery("oppref=%20OpenAI-Click-123%20"),
        )
        assertNull(TrackHub.rawOpenAiOpprefFromEncodedQuery("foo=1&oppref="))
        assertNull(TrackHub.rawOpenAiOpprefFromEncodedQuery("foo=1"))
    }
}
