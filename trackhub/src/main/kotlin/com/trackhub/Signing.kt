package com.trackhub

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SDK Signature (Adjust-style) — must stay byte-for-byte compatible with the
 * server verifier (`src/lib/sdk-signature.ts`) and the iOS SDK.
 *
 * message = "<timestamp>.<ingestToken>.<rawBody>"
 * signature = lowercase-hex HMAC-SHA256(secret, message)
 *
 * Verified against the shared parity vector:
 *   HMAC-SHA256("parity", "123.tok.{\"a\":1}")
 *     = 40d56064e34d01661d8d5f7d8a15d1cab9fe32b9b1c8788cd53c19a4d5a755c5
 */
internal object Signing {

    fun message(timestamp: String, ingestToken: String, rawBody: String): String =
        "$timestamp.$ingestToken.$rawBody"

    fun sign(secret: String, timestamp: String, ingestToken: String, rawBody: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message(timestamp, ingestToken, rawBody).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
