package com.trackhub

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TrackHubInstrumentedTest {
    @Test
    fun signedTestLabPayloadRetriesOfflineWithoutAdvertisingIdPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testToken = "test-run-token-with-enough-entropy-1234"
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        TrackHub.clearOfflineQueueForTest(context, testToken)
        prefs.edit()
            .clear()
            .putString("gclid", "stale_google_click")
            .putString("pending_gclid", "stale_google_click")
            .commit()

        val trackAttempts = AtomicInteger(0)
        val allowTrackRecovery = AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val isTrack = request.path?.contains("/sdk/track") == true
                if (isTrack) trackAttempts.incrementAndGet()
                return if (isTrack && !allowTrackRecovery.get()) {
                    MockResponse().setResponseCode(500).setBody("{}")
                } else MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        server.start()
        try {
            val endpoint = server.url("/").toString().trimEnd('/')
            assertFalse(
                TrackHub.handleDeepLink(
                    context,
                    Uri.parse("https://app.example/open?oppref=${"x".repeat(1025)}"),
                ),
            )
            assertFalse(TrackHub.handleDeepLink(context, Uri.parse("mailto:support@example.com")))
            assertTrue(
                TrackHub.handleDeepLink(
                    context,
                    Uri.parse("https://app.example/open?oppref=chatgpt-click-123"),
                ),
            )
            // Persistence is intentionally off-main-thread to avoid parsing or
            // rewriting SharedPreferences XML in a host lifecycle callback.
            waitUntil { !prefs.contains("gclid") && !prefs.contains("pending_gclid") }

            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey(
                        endpoint,
                        "test-ingest-token-with-enough-entropy-1234",
                        "test-sdk-secret-with-enough-entropy",
                    ),
                    environment = TrackHubEnvironment.TestLab(testToken),
                    debugLogging = true,
                ),
            )

            val opprefPayloads = waitForOpprefPayloads(server)
            assertEquals("chatgpt-click-123", opprefPayloads.getValue("install").getString("oppref"))
            assertEquals("chatgpt-click-123", opprefPayloads.getValue("session").getString("oppref"))

            TrackHub.trackEvent("integration_probe", callbackParams = mapOf("screen" to "paywall"))

            val firstTrack = waitForTrackRequest(server)
            val body = JSONObject(firstTrack.body.readUtf8())
            assertEquals("integration_probe", body.getString("event_name"))
            assertEquals(testToken, body.getString("test_run_token"))
            assertEquals("paywall", body.getJSONObject("callback_params").getString("screen"))
            assertFalse(body.has("revenue_cents"))
            assertNotNull(firstTrack.getHeader("X-TrackHub-Timestamp"))
            assertNotNull(firstTrack.getHeader("X-TrackHub-Signature"))
            waitUntil { TrackHub.offlineQueueCount(context, testToken) == 1 }
            assertFalse(prefs.contains("pending_reports_${TrackHub.offlineQueueNamespace(testToken)}"))
            allowTrackRecovery.set(true)

            // Reconfigure in the same Test Lab namespace: only that queue is
            // drained, and the second dispatcher response succeeds.
            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey(
                        endpoint,
                        "test-ingest-token-with-enough-entropy-1234",
                        "test-sdk-secret-with-enough-entropy",
                    ),
                    environment = TrackHubEnvironment.TestLab(testToken),
                    debugLogging = true,
                ),
            )
            waitForTrackRequest(server)
            waitUntil { TrackHub.offlineQueueCount(context, testToken) == 0 }

            val outageToken = "outage-resilience-token-with-enough-entropy-5678"
            val outageIngestToken = "test-ingest-token-with-enough-entropy-5678"
            val outageSecret = "test-sdk-secret-with-enough-entropy-5678"
            TrackHub.clearOfflineQueueForTest(context, outageToken)
            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey("http://127.0.0.1:9", outageIngestToken, outageSecret),
                    environment = TrackHubEnvironment.TestLab(outageToken),
                ),
            )

            // org.json rejects non-finite numbers. Invalid host-provided
            // callback data must be dropped without escaping as an exception.
            TrackHub.trackEvent(
                "invalid_payload",
                callbackParams = mapOf("invalid_number" to Double.NaN),
            )

            val startedAt = SystemClock.elapsedRealtime()
            repeat(25) { index -> TrackHub.trackEvent("offline_$index") }
            val enqueueCallMs = SystemClock.elapsedRealtime() - startedAt
            assertTrue("public tracking calls blocked for ${enqueueCallMs}ms", enqueueCallMs < 2_000)

            waitUntil(timeoutMs = 10_000) {
                TrackHub.offlineQueuePathCount(context, outageToken, "sdk/track") >= 25
            }

            // Exercise a privacy action before the replacement sdkKey starts.
            // The durable job must belong to the app installation, survive the
            // key rotation, and recover with the new credentials.
            val privacyIngestToken = "privacy-ingest-token-with-enough-entropy-9012"
            val privacySecret = "privacy-sdk-secret-with-enough-entropy-9012"
            val forgotten = AtomicBoolean(false)
            val forgetCompleted = CountDownLatch(1)
            TrackHub.gdprForgetMe(context) { accepted ->
                forgotten.set(accepted)
                forgetCompleted.countDown()
            }
            waitUntil { TrackHub.hasPendingErasureForTest(context, privacyIngestToken) }
            assertTrue(TrackHub.isTrackingStoppedForTest())
            waitUntil {
                TrackHub.offlineQueuePathCount(context, outageToken, "sdk/track") == 0 &&
                    !prefs.contains("openai_oppref") &&
                    !prefs.contains("pending_openai_oppref")
            }
            assertFalse(
                TrackHub.handleDeepLink(
                    context,
                    Uri.parse("https://app.example/open?oppref=must-not-survive-erasure"),
                ),
            )
            assertFalse(prefs.contains("openai_oppref"))

            // The same durable erasure resumes after a later launch against a
            // healthy TrackHub server; tracking never turns back on meanwhile.
            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey(endpoint, privacyIngestToken, privacySecret),
                    environment = TrackHubEnvironment.TestLab(outageToken),
                ),
            )
            assertTrue(forgetCompleted.await(10, TimeUnit.SECONDS))
            assertTrue(forgotten.get())
            assertFalse(TrackHub.hasPendingErasureForTest(context, privacyIngestToken))
            assertTrue(TrackHub.isTrackingStoppedForTest())

            val permissions = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
            assertFalse(permissions.contains("com.google.android.gms.permission.AD_ID"))
            assertTrue(permissions.contains("android.permission.INTERNET"))
        } finally {
            TrackHub.clearOfflineQueueForTest(context, testToken)
            prefs.edit().clear().commit()
            server.shutdown()
        }
    }

    private fun sdkKey(endpoint: String, ingestToken: String, sdkSecret: String): String {
        val raw = JSONObject(mapOf("e" to endpoint, "i" to ingestToken, "s" to sdkSecret)).toString()
        return "thcfg_v1_" + Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun waitForOpprefPayloads(server: MockWebServer): Map<String, JSONObject> {
        val payloads = mutableMapOf<String, JSONObject>()
        repeat(20) {
            val request = server.takeRequest(1, TimeUnit.SECONDS) ?: return@repeat
            val key = when {
                request.path?.endsWith("/install") == true -> "install"
                request.path?.endsWith("/sdk/session") == true -> "session"
                else -> null
            }
            if (key != null) payloads[key] = JSONObject(request.body.readUtf8())
            if (payloads.keys.containsAll(setOf("install", "session"))) return payloads
        }
        throw AssertionError("TrackHub did not send both oppref install and session payloads")
    }

    private fun waitForTrackRequest(server: MockWebServer): RecordedRequest {
        repeat(12) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            if (request != null && request.path?.contains("/sdk/track") == true) return request
        }
        throw AssertionError("TrackHub did not send /sdk/track")
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for SDK state")
    }
}
