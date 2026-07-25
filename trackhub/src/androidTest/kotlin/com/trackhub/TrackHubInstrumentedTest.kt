package com.trackhub

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
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
        val queueKey = "pending_reports_${TrackHub.offlineQueueNamespace(testToken)}"
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        prefs.edit()
            .remove(queueKey)
            .putString("gclid", "stale_google_click")
            .putString("pending_gclid", "stale_google_click")
            .apply()

        val trackAttempts = AtomicInteger(0)
        val erasureHandler: TrackHubBackendPrivacyErasureHandler = { _, _, completion ->
            completion(true)
        }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path?.contains("/sdk/track") == true && trackAttempts.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(500).setBody("{}")
                } else {
                    MockResponse().setResponseCode(200).setBody("{}")
                }
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
            assertTrue(
                TrackHub.handleDeepLink(
                    context,
                    Uri.parse("https://app.example/open?oppref=chatgpt-click-123"),
                ),
            )
            assertFalse(prefs.contains("gclid"))
            assertFalse(prefs.contains("pending_gclid"))

            TrackHub.configure(
                context = context,
                endpoint = endpoint,
                ingestToken = "test-ingest-token-with-enough-entropy-1234",
                userId = "qa-device-user",
                sdkSecret = "test-sdk-secret",
                debug = true,
                integrationTestToken = testToken,
                backendPrivacyErasureHandler = erasureHandler,
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
            waitUntil { JSONArray(prefs.getString(queueKey, "[]")).length() == 1 }

            // Reconfigure in the same Test Lab namespace: only that queue is
            // drained, and the second dispatcher response succeeds.
            TrackHub.configure(
                context = context,
                endpoint = endpoint,
                ingestToken = "test-ingest-token-with-enough-entropy-1234",
                userId = "qa-device-user",
                sdkSecret = "test-sdk-secret",
                integrationTestToken = testToken,
                backendPrivacyErasureHandler = erasureHandler,
            )
            waitForTrackRequest(server)
            waitUntil { JSONArray(prefs.getString(queueKey, "[]")).length() == 0 }

            val forgotten = AtomicBoolean(false)
            val forgetCompleted = CountDownLatch(1)
            TrackHub.forgetDevice { accepted ->
                forgotten.set(accepted)
                forgetCompleted.countDown()
            }
            assertTrue(forgetCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(forgotten.get())
            assertFalse(prefs.contains("openai_oppref"))
            assertFalse(prefs.contains("pending_openai_oppref"))
            assertFalse(
                TrackHub.handleDeepLink(
                    context,
                    Uri.parse("https://app.example/open?oppref=must-not-survive-erasure"),
                ),
            )
            assertFalse(prefs.contains("openai_oppref"))

            val permissions = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
            assertFalse(permissions.contains("com.google.android.gms.permission.AD_ID"))
            assertTrue(permissions.contains("android.permission.INTERNET"))
        } finally {
            prefs.edit().clear().apply()
            server.shutdown()
        }
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

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for SDK state")
    }
}
