package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity with the server verifier and the iOS SDK. The vector below is the same
 * one asserted in `tests/sdk-signature.test.ts` (Node) and the iOS
 * `encoder-tests` — all three implementations must agree byte-for-byte, or the
 * server will reject signed installs.
 */
class SigningTest {

    @Test
    fun matchesSharedParityVector() {
        val sig = Signing.sign(
            secret = "parity",
            timestamp = "123",
            ingestToken = "tok",
            rawBody = "{\"a\":1}",
        )
        assertEquals("40d56064e34d01661d8d5f7d8a15d1cab9fe32b9b1c8788cd53c19a4d5a755c5", sig)
    }

    @Test
    fun messageBindsTimestampTokenAndBody() {
        assertEquals("100.app_tok.{}", Signing.message("100", "app_tok", "{}"))
    }

    @Test
    fun differentBodyProducesDifferentSignature() {
        val a = Signing.sign("s", "1", "t", "{\"x\":1}")
        val b = Signing.sign("s", "1", "t", "{\"x\":2}")
        assert(a != b)
    }
}
