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
            scope = "install",
            rawBody = "{\"a\":1}",
        )
        assertEquals("48bd3ea5529853b246d18a578d1ce79aa50c2d3e7f69663b233bd696c5c8a91d", sig)
    }

    @Test
    fun messageBindsTimestampTokenAndBody() {
        assertEquals(
            "100.app_tok.sdk/session.{}",
            Signing.message("100", "app_tok", "sdk/session", "{}"),
        )
    }

    @Test
    fun differentBodyProducesDifferentSignature() {
        val a = Signing.sign("s", "1", "t", "install", "{\"x\":1}")
        val b = Signing.sign("s", "1", "t", "install", "{\"x\":2}")
        assert(a != b)
    }

    @Test
    fun differentEndpointProducesDifferentSignature() {
        val install = Signing.sign("s", "1", "t", "install", "{}")
        val forget = Signing.sign("s", "1", "t", "sdk/forget-device", "{}")
        assert(install != forget)
    }
}
