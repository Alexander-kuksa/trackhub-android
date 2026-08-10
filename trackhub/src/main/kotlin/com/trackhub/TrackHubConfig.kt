package com.trackhub

import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale

sealed class TrackHubEnvironment {
    data object Production : TrackHubEnvironment()
    data class TestLab(val token: String) : TrackHubEnvironment()

    internal fun testToken(): String? = when (this) {
        Production -> null
        is TestLab -> token.trim().takeIf { it.length in 20..128 }
    }
}

enum class TrackHubConsentStatus {
    GRANTED,
    DENIED,
    UNKNOWN;

    internal fun booleanValue(): Boolean? = when (this) {
        GRANTED -> true
        DENIED -> false
        UNKNOWN -> null
    }
}

data class TrackHubGoogleAdsConsent(
    val adUserData: TrackHubConsentStatus = TrackHubConsentStatus.UNKNOWN,
    val adPersonalization: TrackHubConsentStatus = TrackHubConsentStatus.UNKNOWN,
    val isEea: Boolean? = null,
)

data class TrackHubPiplConsent(
    val personalInformation: TrackHubConsentStatus = TrackHubConsentStatus.UNKNOWN,
    val crossBorderTransfer: TrackHubConsentStatus = TrackHubConsentStatus.UNKNOWN,
    val adsMeasurement: TrackHubConsentStatus = TrackHubConsentStatus.UNKNOWN,
)

/**
 * The complete public TrackHub 3.0 startup configuration. `sdkKey` is copied
 * from TrackHub and contains the app-specific endpoint and ingest credentials.
 * It must never be logged or sent to another service.
 */
data class TrackHubConfig(
    val sdkKey: String,
    val environment: TrackHubEnvironment = TrackHubEnvironment.Production,
    val debugLogging: Boolean = false,
    val countryCode: String? = null,
    val collectAdvertisingId: Boolean = false,
    val firebaseAppInstanceId: String? = null,
    val googleAdsConsent: TrackHubGoogleAdsConsent = TrackHubGoogleAdsConsent(),
    val piplConsent: TrackHubPiplConsent = TrackHubPiplConsent(),
    val attributionChangedHandler: TrackHubAttributionChangedHandler? = null,
    val deferredDeepLinkHandler: TrackHubDeferredDeepLinkHandler? = null,
) {
    // Kotlin data classes include every constructor value in their generated
    // toString(). sdkKey carries signing credentials, so never expose it to
    // host logs, crash reporters, or debug tooling.
    override fun toString(): String = "TrackHubConfig(" +
        "sdkKey=<redacted>, " +
        "environment=${when (environment) {
            TrackHubEnvironment.Production -> "production"
            is TrackHubEnvironment.TestLab -> "testLab(<redacted>)"
        }}, " +
        "debugLogging=$debugLogging, " +
        "countryCode=$countryCode, " +
        "collectAdvertisingId=$collectAdvertisingId, " +
        "firebaseAppInstanceId=${if (firebaseAppInstanceId == null) "null" else "<redacted>"}, " +
        "googleAdsConsent=$googleAdsConsent, " +
        "piplConsent=$piplConsent, " +
        "attributionChangedHandler=${attributionChangedHandler != null}, " +
        "deferredDeepLinkHandler=${deferredDeepLinkHandler != null})"
}

internal data class DecodedTrackHubSdkKey(
    val endpoint: String,
    val ingestToken: String,
    val sdkSecret: String,
) {
    companion object {
        private const val PREFIX = "thcfg_v1_"

        fun decode(value: String): DecodedTrackHubSdkKey? = runCatchingException {
            if (!value.startsWith(PREFIX) || value.length > 8192) return@runCatchingException null
            val encoded = value.removePrefix(PREFIX)
            val raw = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            val json = JSONObject(raw)
            val endpoint = json.getString("e")
            val ingestToken = json.getString("i")
            val sdkSecret = json.getString("s")
            if (!isAllowedEndpoint(endpoint) || ingestToken.length < 20 || sdkSecret.length < 20) {
                return@runCatchingException null
            }
            DecodedTrackHubSdkKey(endpoint.trimEnd('/'), ingestToken, sdkSecret)
        }.getOrNull()

        internal fun isAllowedEndpoint(value: String): Boolean {
            if (value != value.trim()) return false
            return runCatchingException {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host?.trim('[', ']')
                if (uri.isOpaque || uri.userInfo != null || host.isNullOrBlank()) return@runCatchingException false
                if (uri.rawQuery != null || uri.rawFragment != null) return@runCatchingException false
                scheme == "https" || (
                    scheme == "http" && (
                        host.equals("localhost", ignoreCase = true) ||
                            host == "127.0.0.1" ||
                            host == "::1"
                    )
                )
            }.getOrDefault(false)
        }
    }
}
