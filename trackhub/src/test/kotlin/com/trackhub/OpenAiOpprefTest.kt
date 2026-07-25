package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiOpprefTest {
    @Test
    fun trimsAndAcceptsBoundedReference() {
        assertEquals("chatgpt-click-123", TrackHub.normalizedOpenAiOppref("  chatgpt-click-123  "))
        assertEquals("x".repeat(1024), TrackHub.normalizedOpenAiOppref("x".repeat(1024)))
    }

    @Test
    fun rejectsMissingBlankAndOversizedReference() {
        assertNull(TrackHub.normalizedOpenAiOppref(null))
        assertNull(TrackHub.normalizedOpenAiOppref("   "))
        assertNull(TrackHub.normalizedOpenAiOppref("x".repeat(1025)))
    }
}
