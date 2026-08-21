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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TrackHubInstrumentedTest {
    @Test
    fun installIdentityIsCommittedBeforeItCanBeUsed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        prefs.edit().remove("install_uid").commit()
        TrackHub.resetRuntimeCircuitForTest()
        TrackHub.resetVolatileInstallUidForTest()

        val created = TrackHub.installUidForTest(context)
        assertEquals(created, prefs.getString("install_uid", null))

        // Simulate a new process-level read: the stable value must come from
        // the synchronously committed preference, not volatile memory.
        TrackHub.resetVolatileInstallUidForTest()
        assertEquals(created, TrackHub.installUidForTest(context))
    }

    @Test
    fun signedTestLabPayloadRetriesOfflineWithRemoteAdvertisingIdControl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testToken = "test-run-token-with-enough-entropy-1234"
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        TrackHub.clearOfflineQueueForTest(context, testToken)
        prefs.edit()
            .clear()
            .putString("gclid", "stale_google_click")
            .putString("pending_gclid", "stale_google_click")
            .commit()

        val allowTrackRecovery = AtomicBoolean(false)
        val installRequest = AtomicReference<RecordedRequest?>()
        val sessionRequest = AtomicReference<RecordedRequest?>()
        val firstFailedTrackRequest = AtomicReference<RecordedRequest?>()
        val lifecycleRequestsSeen = CountDownLatch(2)
        val remoteConfigSeen = CountDownLatch(1)
        val firstFailedTrackSeen = CountDownLatch(1)
        val recoveredTrackSeen = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/sdk/config") == true) {
                    remoteConfigSeen.countDown()
                    return MockResponse().setResponseCode(200).setBody(
                        "{\"androidAdvertisingIdCollectionEnabled\":false}",
                    )
                }
                when {
                    request.path?.endsWith("/install") == true &&
                        installRequest.compareAndSet(null, request) -> lifecycleRequestsSeen.countDown()

                    request.path?.endsWith("/sdk/session") == true &&
                        sessionRequest.compareAndSet(null, request) -> lifecycleRequestsSeen.countDown()
                }
                val isTrack = request.path?.contains("/sdk/track") == true
                // Snapshot the response decision before releasing the test
                // thread. Otherwise it can flip allowTrackRecovery between
                // the latch countdown and the return below, turning the first
                // request into a 200 while the test still waits for a retry.
                val shouldFailTrack = isTrack && !allowTrackRecovery.get()
                if (isTrack) {
                    if (!shouldFailTrack) {
                        recoveredTrackSeen.countDown()
                    } else if (firstFailedTrackRequest.compareAndSet(null, request)) {
                        firstFailedTrackSeen.countDown()
                    }
                }
                return if (shouldFailTrack) {
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
            waitUntil("legacy click state cleanup") {
                !prefs.contains("gclid") && !prefs.contains("pending_gclid")
            }

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

            assertTrue(
                "TrackHub did not send both oppref install and session payloads",
                lifecycleRequestsSeen.await(120, TimeUnit.SECONDS),
            )
            val installBody = JSONObject(requireNotNull(installRequest.get()).body.readUtf8())
            val sessionBody = JSONObject(requireNotNull(sessionRequest.get()).body.readUtf8())
            assertTrue("TrackHub did not fetch remote SDK config", remoteConfigSeen.await(30, TimeUnit.SECONDS))
            assertEquals("chatgpt-click-123", installBody.getString("oppref"))
            assertEquals("chatgpt-click-123", sessionBody.getString("oppref"))
            assertFalse(installBody.has("device_id"))

            TrackHub.trackEvent("integration_probe", callbackParams = mapOf("screen" to "paywall"))

            assertTrue(
                "TrackHub did not send /sdk/track",
                firstFailedTrackSeen.await(120, TimeUnit.SECONDS),
            )
            val firstTrack = requireNotNull(firstFailedTrackRequest.get())
            val body = JSONObject(firstTrack.body.readUtf8())
            assertEquals("integration_probe", body.getString("event_name"))
            assertEquals(testToken, body.getString("test_run_token"))
            assertEquals("paywall", body.getJSONObject("callback_params").getString("screen"))
            assertFalse(body.has("revenue_cents"))
            assertNotNull(firstTrack.getHeader("X-TrackHub-Timestamp"))
            assertNotNull(firstTrack.getHeader("X-TrackHub-Signature"))
            waitUntil("failed event persisted in offline queue") {
                TrackHub.offlineQueueCount(context, testToken) == 1
            }
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
            assertTrue(
                "TrackHub did not retry /sdk/track after recovery",
                recoveredTrackSeen.await(120, TimeUnit.SECONDS),
            )
            waitUntil("offline queue recovery") {
                TrackHub.offlineQueueCount(context, testToken) == 0
            }

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

            // Each event is committed with AtomicFile/fsync. A cold x86 CI
            // emulator can take more than an arbitrary wall-clock polling
            // budget for 25 commits, so wait for a FIFO marker on the actual
            // state executor before inspecting the durable queue.
            val stateDrained = TrackHub.awaitStateIdleForTest(240_000)
            assertTrue(
                "SDK state executor did not drain after server outage; " +
                    "persisted ${TrackHub.offlineQueuePathCount(context, outageToken, "sdk/track")}/25 events",
                stateDrained,
            )
            val queuedOutageEvents =
                TrackHub.offlineQueuePathCount(context, outageToken, "sdk/track")
            assertTrue(
                "expected 25 durable outage events, found $queuedOutageEvents",
                queuedOutageEvents >= 25,
            )
            assertFalse(TrackHub.runtimeCircuitOpenForTest())

            // Exercise a privacy action before the replacement sdkKey starts.
            // The durable job must belong to the app installation, survive the
            // key rotation, and recover with the new credentials.
            val privacyIngestToken = "privacy-ingest-token-with-enough-entropy-9012"
            val privacySecret = "privacy-sdk-secret-with-enough-entropy-9012"
            val forgotten = AtomicBoolean(false)
            val forgetCompleted = CountDownLatch(1)
            TrackHub.openRuntimeCircuitForTest()
            TrackHub.gdprForgetMe(context) { accepted ->
                forgotten.set(accepted)
                forgetCompleted.countDown()
            }
            waitUntil("privacy erasure persistence") {
                TrackHub.hasPendingErasureForTest(context, privacyIngestToken)
            }
            assertTrue(TrackHub.isTrackingStoppedForTest())
            waitUntil("privacy measurement cleanup") {
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
            TrackHub.resetRuntimeCircuitForTest() // simulate a clean process launch
            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey(endpoint, privacyIngestToken, privacySecret),
                    environment = TrackHubEnvironment.TestLab(outageToken),
                ),
            )
            assertTrue(forgetCompleted.await(30, TimeUnit.SECONDS))
            assertTrue(forgotten.get())
            assertFalse(TrackHub.hasPendingErasureForTest(context, privacyIngestToken))
            assertTrue(TrackHub.isTrackingStoppedForTest())

            val permissions = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
            assertTrue(permissions.contains("com.google.android.gms.permission.AD_ID"))
            assertTrue(permissions.contains("android.permission.INTERNET"))
        } finally {
            TrackHub.resetRuntimeCircuitForTest()
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

    private fun waitUntil(
        description: String,
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for SDK state: $description")
    }
}
