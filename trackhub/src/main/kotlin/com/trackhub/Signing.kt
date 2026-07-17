package com.trackhub

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SDK Signature (Adjust-style) — must stay byte-for-byte compatible with the
 * server verifier (`src/lib/sdk-signature.ts`) and the iOS SDK.
 *
 * v2 message = "<timestamp>.<ingestToken>.<endpointScope>.<rawBody>"
 * signature = lowercase-hex HMAC-SHA256(secret, message)
 *
 * Verified against the shared parity vector:
 * Scope binding prevents replaying (for example) a signed event body against
 * a more privileged SDK endpoint.
 */
internal object Signing {

    fun message(timestamp: String, ingestToken: String, scope: String, rawBody: String): String =
        "$timestamp.$ingestToken.${scope.trim('/')}.$rawBody"

    fun sign(
        secret: String,
        timestamp: String,
        ingestToken: String,
        scope: String,
        rawBody: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message(timestamp, ingestToken, scope, rawBody).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
