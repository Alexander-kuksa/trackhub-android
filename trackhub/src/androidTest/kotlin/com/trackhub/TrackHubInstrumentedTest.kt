package com.trackhub

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

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
    fun firstOpenTimestampIsCommittedBeforeItCanBeUsed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        prefs.edit().remove("first_open_at_ms").commit()
        TrackHub.resetRuntimeCircuitForTest()
        TrackHub.resetVolatileFirstOpenAtForTest()

        val created = TrackHub.firstOpenAtForTest(context).time
        assertEquals(created, prefs.getLong("first_open_at_ms", 0L))
        assertTrue(created > 0L)

        // Simulate a process-level reread. The same value must come from the
        // synchronously committed preference, not process memory.
        TrackHub.resetVolatileFirstOpenAtForTest()
        assertEquals(created, TrackHub.firstOpenAtForTest(context).time)
        assertFalse(TrackHub.runtimeCircuitOpenForTest())
    }

    @Test
    fun signedTestLabPayloadRetriesOfflineWithRemoteAdvertisingIdControl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testToken = "test-run-token-with-enough-entropy-1234"
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        // Connected tests can reinstall the same APK without clearing app
        // data. Remove only this test app's SDK files, including a privacy job
        // retained by a failed previous run, before starting a fresh scenario.
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("trackhub-") }
            ?.forEach { it.delete() }
        TrackHub.clearOfflineQueueForTest(context, testToken)
        prefs.edit()
            .clear()
            .putString("gclid", "stale_google_click")
            .putString("pending_gclid", "stale_google_click")
            .commit()

        val allowTrackRecovery = AtomicBoolean(false)
        val installRequest = AtomicReference<RecordedRequest?>()
        val sessionRequest = AtomicReference<RecordedRequest?>()
        val wbraidSessionRequest = AtomicReference<RecordedRequest?>()
        val firstFailedTrackRequest = AtomicReference<RecordedRequest?>()
        val lifecycleRequestsSeen = CountDownLatch(2)
        val wbraidSessionSeen = CountDownLatch(1)
        val remoteConfigSeen = CountDownLatch(1)
        val remoteConfigResponded = AtomicBoolean(false)
        val installObservedResolvedConfig = AtomicBoolean(false)
        val firstFailedTrackSeen = CountDownLatch(1)
        val recoveredTrackSeen = CountDownLatch(1)
        val salesRequests = ConcurrentHashMap<String, JSONObject>()
        val salesSeen = CountDownLatch(3)
        val credentialsAttempts = AtomicInteger(0)
        val credentialsNotifications = AtomicInteger(0)
        val credentialsFailureSeen = CountDownLatch(1)
        val credentialsFailure = AtomicReference<TrackHubDeliveryFailure?>()
        val callbackOnMain = AtomicBoolean(false)
        val correctedSignatureTime = AtomicReference<Long?>()
        val credentialProbeToken = "credential-probe-ingest-token-with-enough-entropy"
        val serverTimeMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/sdk/config") == true) {
                    remoteConfigSeen.countDown()
                    remoteConfigResponded.set(true)
                    return MockResponse().setResponseCode(200).setBody(
                        "{\"androidAdvertisingIdCollectionEnabled\":false}",
                    )
                }
                if (request.path?.contains("/ingest/$credentialProbeToken/") == true) {
                    if (credentialsAttempts.incrementAndGet() == 1) {
                        return MockResponse().setResponseCode(401).setBody(
                            JSONObject().put("error", "clock_skew").put("server_time_ms", serverTimeMs).toString(),
                        )
                    }
                    correctedSignatureTime.set(request.getHeader("X-TrackHub-Timestamp")?.toLongOrNull())
                    return MockResponse().setResponseCode(401).setBody("{}")
                }
                when {
                    request.path?.endsWith("/install") == true &&
                        installRequest.compareAndSet(null, request) -> {
                            installObservedResolvedConfig.set(remoteConfigResponded.get())
                            lifecycleRequestsSeen.countDown()
                        }

                    request.path?.endsWith("/sdk/session") == true &&
                        sessionRequest.compareAndSet(null, request) -> lifecycleRequestsSeen.countDown()

                    request.path?.endsWith("/sdk/session") == true &&
                        wbraidSessionRequest.compareAndSet(null, request) -> wbraidSessionSeen.countDown()
                }
                val isTrack = request.path?.contains("/sdk/track") == true
                // Snapshot the response decision before releasing the test
                // thread. Otherwise it can flip allowTrackRecovery between
                // the latch countdown and the return below, turning the first
                // request into a 200 while the test still waits for a retry.
                val shouldFailTrack = isTrack && !allowTrackRecovery.get()
                if (isTrack) {
                    val body = JSONObject(request.body.clone().readUtf8())
                    val name = body.optString("event_name")
                    if (name in setOf("ob_shown", "pw_shown", "purchase_cta_tapped") &&
                        salesRequests.putIfAbsent(name, body) == null
                    ) salesSeen.countDown()
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
            assertTrue("initial install raced ahead of resolved remote config", installObservedResolvedConfig.get())
            assertEquals("chatgpt-click-123", installBody.getString("oppref"))
            assertEquals("chatgpt-click-123", sessionBody.getString("oppref"))
            assertFalse(installBody.has("device_id"))

            TrackHub.setGoogleClickIds(wbraid = "web-to-app-MixedCase-123")
            assertTrue("wbraid re-engagement session was not delivered", wbraidSessionSeen.await(30, TimeUnit.SECONDS))
            val wbraidBody = JSONObject(requireNotNull(wbraidSessionRequest.get()).body.readUtf8())
            assertEquals("web-to-app-MixedCase-123", wbraidBody.getString("wbraid"))
            assertFalse(wbraidBody.has("oppref"))

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

            TrackHub.trackOnboardingShown(
                callbackParams = mapOf("placement_name" to "must-be-removed", "screen" to "welcome"),
                partnerParams = mapOf("variant" to "A"),
                deduplicationId = "onboarding-v1",
            )
            TrackHub.trackPaywallShown(TrackHubSalesPlacement.ONBOARDING)
            TrackHub.trackPurchaseCtaTapped(TrackHubSalesPlacement.IN_APP)
            assertTrue("sales helper events were not delivered", salesSeen.await(30, TimeUnit.SECONDS))
            val onboarding = requireNotNull(salesRequests["ob_shown"])
            assertFalse(onboarding.getJSONObject("callback_params").has("placement_name"))
            assertEquals("welcome", onboarding.getJSONObject("callback_params").getString("screen"))
            assertEquals("A", onboarding.getJSONObject("partner_params").getString("variant"))
            assertEquals(
                TrackHub.deduplicatedClientEventId(onboarding.getString("install_uid"), "ob_shown", "onboarding-v1"),
                onboarding.getString("client_event_id"),
            )
            assertEquals("onboarding_placement", salesRequests["pw_shown"]!!.getJSONObject("callback_params").getString("placement_name"))
            assertEquals("inapp_placement", salesRequests["purchase_cta_tapped"]!!.getJSONObject("callback_params").getString("placement_name"))
            waitUntil("sales events acknowledged") { TrackHub.offlineQueueCount(context, testToken) == 0 }
            productionEventsWaitForInstallAndFirebaseBackfills(context)

            // A recoverable signing-clock response must retry before notifying
            // the host. Only the final rejection stops delivery, once, on main.
            TrackHub.start(
                context,
                TrackHubConfig(
                    sdkKey = sdkKey(endpoint, credentialProbeToken, "test-credential-probe-secret"),
                    environment = TrackHubEnvironment.TestLab("credential-probe-test-token-with-enough-entropy"),
                    deliveryFailureHandler = { failure ->
                        credentialsNotifications.incrementAndGet()
                        credentialsFailure.set(failure)
                        callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                        credentialsFailureSeen.countDown()
                    },
                ),
            )
            assertTrue("credential failure was not reported", credentialsFailureSeen.await(30, TimeUnit.SECONDS))
            assertEquals(2, credentialsAttempts.get())
            assertEquals(1, credentialsNotifications.get())
            assertTrue(credentialsFailure.get() is TrackHubDeliveryFailure.CredentialsRejected)
            assertTrue(callbackOnMain.get())
            assertTrue(requireNotNull(correctedSignatureTime.get()) >= serverTimeMs - 2_000)
            assertTrue(TrackHub.runtimeCircuitOpenForTest())

            TrackHub.resetRuntimeCircuitForTest() // simulate the next process with a fresh SDK Key

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
                    !prefs.contains("pending_openai_oppref") &&
                    !prefs.contains("firebase_app_instance_id") &&
                    !prefs.contains("first_open_at_ms")
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

    private fun productionEventsWaitForInstallAndFirebaseBackfills(context: Context) {
        val prefs = context.getSharedPreferences("trackhub", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        TrackHub.resetVolatileInstallUidForTest()
        TrackHub.resetVolatileFirstOpenAtForTest()
        val configSeen = CountDownLatch(1)
        val releaseConfig = CountDownLatch(1)
        val eventSeen = CountDownLatch(1)
        val firebaseSeen = CountDownLatch(1)
        val posts = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
        val installAttempts = AtomicInteger(0)
        val productionToken = "production-attribution-test-token-123456"
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/sdk/config") == true) {
                    configSeen.countDown()
                    releaseConfig.await(10, TimeUnit.SECONDS)
                    return MockResponse().setBody("{\"androidAdvertisingIdCollectionEnabled\":false}")
                }
                if (request.method == "POST") {
                    val body = JSONObject(request.body.clone().readUtf8())
                    posts.add(request.path.orEmpty() to body)
                    assertFalse(body.has("test_run_token"))
                    if (request.path?.endsWith("/install") == true) {
                        if (installAttempts.incrementAndGet() == 1) return MockResponse().setResponseCode(503)
                        if (body.optString("app_instance_id") == "firebase-late-id") firebaseSeen.countDown()
                    }
                    if (request.path?.endsWith("/sdk/track") == true) eventSeen.countDown()
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        try {
            val key = sdkKey(server.url("/").toString().trimEnd('/'), productionToken, "production-test-secret")
            TrackHub.start(context, TrackHubConfig(sdkKey = key))
            TrackHub.trackEvent("pw_shown", deduplicationId = "first-paywall")
            assertTrue(configSeen.await(10, TimeUnit.SECONDS))
            assertTrue(TrackHub.awaitStateIdleForTest(5_000))
            assertTrue("an event overtook the pending first install", posts.isEmpty())
            releaseConfig.countDown()
            assertTrue("production event did not recover after install retry", eventSeen.await(30, TimeUnit.SECONDS))
            val captured = synchronized(posts) { posts.toList() }
            assertTrue(captured[0].first.endsWith("/install"))
            assertTrue(captured[1].first.endsWith("/install"))
            val first = captured[0].second
            assertEquals(first.toString(), captured[1].second.toString())
            val event = captured.first { it.first.endsWith("/sdk/track") }.second
            assertEquals(first.getString("install_uid"), event.getString("install_uid"))
            assertEquals(first.getString("occurred_at"), event.getString("first_open_at"))
            assertTrue(prefs.getBoolean("install_sent", false))

            TrackHub.updateFirebaseAppInstanceId("firebase-late-id")
            assertTrue("late Firebase ID never reached install context", firebaseSeen.await(10, TimeUnit.SECONDS))
            assertEquals("firebase-late-id", prefs.getString("firebase_app_instance_id", null))
            waitUntil("production queue drained") {
                TrackHub.offlineQueuePathCount(context, null, "install") == 0 &&
                    TrackHub.offlineQueuePathCount(context, null, "sdk/session") == 0 &&
                    TrackHub.offlineQueuePathCount(context, null, "sdk/track") == 0
            }
        } finally {
            releaseConfig.countDown()
            TrackHub.clearOfflineQueueForTest(context, null)
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
