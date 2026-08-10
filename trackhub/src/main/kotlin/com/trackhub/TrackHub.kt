package com.trackhub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.Tasks
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

data class TrackHubAttribution(
    val revision: String,
    val status: String,
    val network: String,
    val channel: String,
    val campaignId: String?,
    val adGroupId: String?,
    val keywordId: String?,
    val touchpointKind: String?,
    val source: String?,
    val data: Map<String, String>,
)

typealias TrackHubAttributionChangedHandler = (TrackHubAttribution) -> Unit
typealias TrackHubDeferredDeepLinkHandler = (String?) -> Unit

enum class TrackHubSalesPlacement(val value: String) {
    ONBOARDING("onboarding_placement"),
    IN_APP("inapp_placement"),
    SPECIAL("special_placement"),
    SETTINGS("settings_placement"),
    ON_LAUNCH("on_launch_placement"),
    QUICK_ACTION("quick_action_placement"),
    TRANSACTION_ABANDONMENT("transaction_abandonment_placement"),
}

enum class TrackHubSalesEvent(val value: String) {
    ONBOARDING_SHOWN("ob_shown"),
    PAYWALL_SHOWN("pw_shown"),
    PURCHASE_CTA_TAPPED("purchase_cta_tapped"),
}

/**
 * TrackHub Android SDK — installs, sessions, custom engagement events and the
 * privacy-preserving App Conversion purchase bridge. Revenue stays server-side;
 * the SDK never accepts a price or currency or imports a billing SDK.
 *
 *  - HTTPS is enforced (token would otherwise leak in transit).
 *  - The ingest token and SDK secret live in memory only — never written to
 *    disk and never logged (debug logging prints status, never credentials).
 *  - Install reports are HMAC-signed when an SDK secret is configured.
 *  - Failed measurement reports use a bounded at-least-once offline queue.
 *  - Purchase observations contain only transaction/product identity; the
 *    configured server billing source supplies value/currency.
 *
 * Usage (on app launch):
 * ```
 * TrackHub.start(applicationContext, TrackHubConfig(sdkKey = "<TrackHub SDK Key>"))
 * ```
 */
// Queue and privacy mutations run on the dedicated `io` executor. The event
// queue uses AtomicFile; small privacy flags still use synchronous commit().
@SuppressLint("ApplySharedPref")
object TrackHub {

    /** SDK version reported to the platform for integration detection. */
    const val SDK_VERSION = "3.0.3"

    private const val PREFS = "trackhub"
    private const val INSTALL_SENT_KEY = "install_sent"
    private const val FIRST_OPEN_AT_KEY = "first_open_at_ms"
    private const val PENDING_REPORTS_KEY = "pending_reports"
    private const val SESSION_SEQ_KEY = "session_seq"
    private const val LAST_BACKGROUND_KEY = "last_background_ms"
    private const val INSTALL_UID_KEY = "install_uid"
    private const val ADVERTISING_ID_KEY = "advertising_id"
    private const val APP_SET_ID_KEY = "app_set_id"
    private const val LIMIT_AD_TRACKING_KEY = "limit_ad_tracking"
    private const val PENDING_GCLID_KEY = "pending_gclid"
    private const val PENDING_GBRAID_KEY = "pending_gbraid"
    private const val GCLID_KEY = "gclid"
    private const val GBRAID_KEY = "gbraid"
    private const val OPENAI_OPPREF_KEY = "openai_oppref"
    private const val PENDING_OPENAI_OPPREF_KEY = "pending_openai_oppref"
    private const val COUNTRY_CODE_KEY = "country_code"
    // SDK 3.0.2 briefly persisted server-returned geography. 3.0.3 returns
    // geography ownership to the server and removes that retired local state.
    private val RETIRED_MEASUREMENT_GEO_KEYS = arrayOf(
        "measurement_geo_country_v1",
        "measurement_geo_eea_v1",
        "measurement_geo_install_uid_v1",
        "measurement_geo_refresh_terminal_v1",
    )
    private const val RETIRED_MEASUREMENT_GEO_REFRESH_KIND = "install_geo_refresh"
    private const val RETIRED_MEASUREMENT_GEO_REFRESH_DEDUPE_KEY = "install_geo_refresh_v1"
    private const val PUSH_TOKEN_KEY = "push_token_fcm"
    private const val EXTERNAL_IDENTITIES_KEY = "external_identities_v3"
    private const val EXTERNAL_IDENTITY_ACK_KEY = "external_identity_ack_v3"
    private const val DEFERRED_RESOLVE_PREFIX = "deferred_resolve_"
    private const val DEFERRED_MATCH_TOKEN_KEY = "deferred_match_token"
    private const val PRIVACY_DISABLED_KEY = "privacy_disabled_v2"
    private const val PENDING_ERASURE_KEY = "pending_erasure_v2"
    private const val LEGACY_PRIVACY_DISABLED_PREFIX = "privacy_disabled_"
    private const val LEGACY_PENDING_ERASURE_PREFIX = "pending_erasure_"
    private const val INSTALL_CREDENTIAL_BOOTSTRAP_PREFIX = "install_credential_bootstrap_"
    private const val RUNTIME_CIRCUIT_MARKER_KEY = "runtime_circuit_last_run_v1"
    private const val AD_USER_DATA_KEY = "consent_ad_user_data"
    private const val AD_PERSONALIZATION_KEY = "consent_ad_personalization"
    private const val EEA_KEY = "consent_eea"
    private const val PIPL_CONSENT_KEY = "consent_pipl"
    private const val CROSS_BORDER_TRANSFER_CONSENT_KEY = "consent_cross_border_transfer"
    private const val ADS_MEASUREMENT_CONSENT_KEY = "consent_ads_measurement"
    private const val SESSION_TIMEOUT_MS = 30 * 60_000L
    private const val MAX_PENDING_REPORTS = 1000
    private const val MAX_PENDING_BYTES = 4 * 1024 * 1024
    private const val MAX_REPORT_BYTES = 64 * 1024
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_RESPONSE_READ_MS = 15_000L
    private const val CALLBACK_TIMEOUT_MS = 15_000L
    private const val RETRY_BASE_MS = 1_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
    private const val CREDENTIAL_BOOTSTRAP_INTERVAL_MS = 24 * 60 * 60_000L
    private val runtimeCircuitOpen = AtomicBoolean(false)

    private enum class RuntimeCircuitReason(val wireValue: String) {
        ALGORITHM("algorithm"),
        STORAGE("storage"),
        CREDENTIALS("credentials"),
    }
    private val rawIo = Executors.newSingleThreadExecutor()
    private val io: Executor = failSilentExecutor("state", rawIo)
    // Network work must never occupy the state/persistence executor. New
    // events are durably spooled by `io` while this single delivery worker is
    // waiting on an unavailable TrackHub endpoint.
    private val rawDelivery = Executors.newSingleThreadExecutor()
    private val delivery: Executor = failSilentExecutor("delivery", rawDelivery)
    private val rawAuxiliaryNetwork = Executors.newFixedThreadPool(2)
    private val auxiliaryNetwork: Executor = failSilentExecutor("auxiliary network", rawAuxiliaryNetwork)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val privacyStateLock = Any()
    private val privacyCallbackLock = Any()
    private val identityStateLock = Any()

    @Volatile private var endpoint: String? = null
    @Volatile private var ingestToken: String? = null
    @Volatile private var sdkSecret: String? = null
    @Volatile private var firebaseAppInstanceId: String? = null
    @Volatile private var integrationTestToken: String? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var pendingGclid: String? = null
    @Volatile private var pendingGbraid: String? = null
    @Volatile private var pendingOpenAiOppref: String? = null
    @Volatile private var debug = false
    @Volatile private var lifecycleRegistered = false
    @Volatile private var collectAdvertisingId = false
    @Volatile private var attributionChangedHandler: TrackHubAttributionChangedHandler? = null
    @Volatile private var deferredDeepLinkHandler: TrackHubDeferredDeepLinkHandler? = null
    @Volatile private var currentAttribution: TrackHubAttribution? = null
    @Volatile private var attributionFetchInFlight = false
    @Volatile private var deferredResolveInFlight = false
    @Volatile private var trackingDisabled = false
    private val privacyStopRequested = AtomicBoolean(false)
    @Volatile private var erasureInFlight = false
    @Volatile private var pendingErasureCompletion: ((Boolean) -> Unit)? = null
    // Accessed only from `io`.
    private var deliveryInFlight = false
    private var retryGeneration = 0L
    private var retryScheduledAtMs = 0L
    private var transientRetryNotBeforeMs = 0L
    // Corrects a bad device wall clock after the server returns its trusted
    // current time. Process-local by design; every queued request is re-signed.
    @Volatile private var clockOffsetMs = 0L
    private var startedActivities = 0

    private data class NetworkConfig(
        val endpoint: String,
        val ingestToken: String,
        val sdkSecret: String?,
    )

    private data class PendingDelivery(
        val id: String,
        val path: String,
        val body: String,
        val kind: String?,
        val attempts: Int,
        val nextAttemptAtMs: Long,
    )

    internal fun isAllowedEndpoint(value: String): Boolean =
        DecodedTrackHubSdkKey.isAllowedEndpoint(value)

    @JvmStatic
    fun start(
        context: Context,
        configuration: TrackHubConfig,
    ) {
        if (runtimeCircuitOpen.get()) {
            log("runtime circuit is open — SDK remains disabled until app restart")
            return
        }
        val decoded = DecodedTrackHubSdkKey.decode(configuration.sdkKey)
        val requestedTestToken = configuration.environment.testToken()
        if (decoded == null || (configuration.environment is TrackHubEnvironment.TestLab && requestedTestToken == null)) {
            log("invalid SDK key or Test Lab token — SDK not started")
            return
        }
        val configuredAppContext = context.applicationContext
        // Publish the privacy namespace synchronously. gdprForgetMe() may be
        // called immediately after start(), before the IO initialization task;
        // it must still persist an erasure job and win that race.
        this.appContext = configuredAppContext
        this.endpoint = decoded.endpoint
        this.ingestToken = decoded.ingestToken
        this.sdkSecret = decoded.sdkSecret
        // SharedPreferences may synchronously parse a legacy multi-megabyte
        // queue XML. Keep every storage read/migration off the host app's main
        // thread; start is intentionally non-blocking.
        io.execute {
            startOnIo(
                configuredAppContext,
                decoded,
                configuration,
                requestedTestToken,
            )
        }
    }

    private fun startOnIo(
        configuredAppContext: Context,
        decoded: DecodedTrackHubSdkKey,
        configuration: TrackHubConfig,
        requestedTestToken: String?,
    ) {
        val prefs = configuredAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.endpoint = decoded.endpoint
        this.ingestToken = decoded.ingestToken
        this.sdkSecret = decoded.sdkSecret
        this.integrationTestToken = requestedTestToken
        this.collectAdvertisingId = configuration.collectAdvertisingId
        this.attributionChangedHandler = configuration.attributionChangedHandler
        this.deferredDeepLinkHandler = configuration.deferredDeepLinkHandler
        this.debug = configuration.debugLogging
        this.appContext = configuredAppContext
        migrateLegacyPrivacyState(configuredAppContext)
        val privacyComplete = hasPersistedPrivacyDisable(configuredAppContext)
        var privacyPendingJob = loadPendingErasure(configuredAppContext)
        // If the app died after persisting the local stop but before the job
        // file reached storage, reconstruct it from the retained install UID.
        if (privacyComplete && privacyPendingJob == null) {
            prefs.getString(INSTALL_UID_KEY, null)?.takeIf(::isUuid)?.let { retainedInstallUid ->
                val recovered = JSONObject()
                    .put("install_uid", retainedInstallUid)
                    .put("reason", "user_requested")
                    .put("attempts", 0)
                    .put("next_attempt_at_ms", 0L)
                if (persistPendingErasure(configuredAppContext, recovered)) {
                    privacyPendingJob = recovered
                }
            }
        }
        val privacyPending = privacyPendingJob != null
            || hasPendingErasureState(configuredAppContext)
        if (privacyStopRequested.get() || privacyPending || privacyComplete) {
            privacyStopRequested.set(true)
            trackingDisabled = true
            clearLocalMeasurementState(configuredAppContext, keepInstallCredential = privacyPendingJob != null)
            runOnMain { registerLifecycle(configuredAppContext) }
            if (privacyPendingJob != null) retryPendingErasure()
            log(if (privacyPending) "privacy erasure pending — tracking remains disabled" else "tracking disabled after privacy erasure")
            return
        }
        privacyStopRequested.set(false)
        trackingDisabled = false
        this.firebaseAppInstanceId = configuration.firebaseAppInstanceId?.takeIf { it.isNotBlank() }
        val prefsEdit = prefs.edit()
        normalizedCountryCode(configuration.countryCode)?.let { prefsEdit.putString(COUNTRY_CODE_KEY, it) }
        applyGoogleAdsConsent(prefsEdit, configuration.googleAdsConsent)
        applyPiplConsent(prefsEdit, configuration.piplConsent)
        val hasPendingGoogleReference = pendingGclid != null || pendingGbraid != null
        if (pendingOpenAiOppref != null && !hasPendingGoogleReference) {
            prefsEdit
                .remove(PENDING_GCLID_KEY)
                .remove(PENDING_GBRAID_KEY)
                .remove(GCLID_KEY)
                .remove(GBRAID_KEY)
        } else if (hasPendingGoogleReference && pendingOpenAiOppref == null) {
            prefsEdit.remove(OPENAI_OPPREF_KEY).remove(PENDING_OPENAI_OPPREF_KEY)
        }
        pendingGclid?.let { prefsEdit.putString(PENDING_GCLID_KEY, it).putString(GCLID_KEY, it) }
        pendingGbraid?.let { prefsEdit.putString(PENDING_GBRAID_KEY, it).putString(GBRAID_KEY, it) }
        pendingOpenAiOppref?.let {
            prefsEdit
                .putString(OPENAI_OPPREF_KEY, it)
                .putString(PENDING_OPENAI_OPPREF_KEY, it)
        }
        prefsEdit.apply()
        purgeRetiredMeasurementGeographyState(configuredAppContext, prefs)
        firstOpenAt(configuredAppContext)
        reportRuntimeCircuitDiagnosticIfNeeded(configuredAppContext)
        runOnMain { registerLifecycle(configuredAppContext) }
        val waitingForInstallQueue = reportInstallIfNeeded(configuredAppContext)
        syncPersistedExternalIdentities(configuredAppContext)
        reportPushTokenIfAvailable(configuredAppContext)
        if (!waitingForInstallQueue) beginSessionIfNeeded(configuredAppContext)
        flushPending(configuredAppContext)
        fetchAttributionIfNeeded()
        if (prefs.getBoolean(INSTALL_SENT_KEY, false)) {
            resolveDeferredDeepLinkIfNeeded()
        }
    }

    /**
     * Provide the Firebase `app_instance_id` (from
     * `FirebaseAnalytics.getAppInstanceId()`), the GA4/Firebase join key. TrackHub
     * persists it on the install and stamps it on forwarded conversions so a
     * server-confirmed subscription attributes to the right install/campaign.
     * TrackHub does NOT depend on Firebase — the host passes the id in. Call
     * Pass it in [TrackHubConfig] at startup when possible.
     */
    @JvmStatic
    fun updateFirebaseAppInstanceId(appInstanceId: String) {
        if (!privacyStopRequested.get() && !runtimeCircuitOpen.get() && appInstanceId.isNotEmpty()) {
            firebaseAppInstanceId = appInstanceId
        }
    }

    /**
     * Bind or clear an optional billing identity without importing that
     * provider's SDK. Apphud, RevenueCat and custom providers are independent.
     */
    @JvmStatic
    fun setExternalIdentity(provider: String, userId: String?) {
        if (privacyStopRequested.get() || runtimeCircuitOpen.get()) return
        val namespace = normalizedExternalProvider(provider) ?: return
        val value = userId?.trim()
        if (value != null && (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > 256)) return
        io.execute {
            val context = appContext ?: return@execute
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val desired = runCatchingException {
                JSONObject(prefs.getString(EXTERNAL_IDENTITIES_KEY, "{}") ?: "{}")
            }.getOrDefault(JSONObject())
            desired.put(namespace, value ?: "")
            prefs.edit().putString(EXTERNAL_IDENTITIES_KEY, desired.toString()).apply()
            syncExternalIdentity(context, namespace, value)
        }
    }

    internal fun normalizedExternalProvider(raw: String): String? {
        val provider = raw.trim().lowercase(Locale.US)
        if (provider == "apphud" || provider == "revenuecat") return provider
        if (!provider.startsWith("custom:")) return null
        val slug = provider.removePrefix("custom:")
        return provider.takeIf {
            slug.length in 1..63 &&
                (slug.first() in 'a'..'z' || slug.first() in '0'..'9') &&
                slug.all { char -> char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-' }
        }
    }

    /**
     * Set the actual ISO-3166 country where measurement originates. Device
     * language/Locale is intentionally not used as geography. A trusted server
     * edge may override this value from its geo header.
     */
    @JvmStatic
    fun updateCountryCode(countryCode: String) {
        if (runtimeCircuitOpen.get()) return
        val value = normalizedCountryCode(countryCode) ?: return
        io.execute {
            val configured = appContext ?: return@execute
            if (trackingDisabled || hasPersistedPrivacyDisable(configured)) return@execute
            configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(COUNTRY_CODE_KEY, value).apply()
        }
    }

    internal fun normalizedCountryCode(raw: String?): String? {
        val value = raw?.trim()?.uppercase(Locale.US) ?: return null
        return value.takeIf { it.length == 2 && it != "XX" && it.all { c -> c in 'A'..'Z' } }
    }

    /** Persist Consent Mode signals and re-report them when already configured. */
    @JvmStatic
    fun updateGoogleAdsConsent(consent: TrackHubGoogleAdsConsent) {
        if (runtimeCircuitOpen.get()) return
        io.execute {
            val configured = appContext ?: return@execute
            if (trackingDisabled || hasPersistedPrivacyDisable(configured)) return@execute
            val prefs = configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            applyGoogleAdsConsent(prefs.edit(), consent).apply()
            if (prefs.getBoolean(INSTALL_SENT_KEY, false)) sendConsentUpdate(configured)
        }
    }

    /** Persist mainland-China PIPL signals and re-report them when configured. */
    @JvmStatic
    fun updatePiplConsent(consent: TrackHubPiplConsent) {
        if (runtimeCircuitOpen.get()) return
        io.execute {
            val configured = appContext ?: return@execute
            if (trackingDisabled || hasPersistedPrivacyDisable(configured)) return@execute
            val prefs = configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            applyPiplConsent(prefs.edit(), consent).apply()
            if (prefs.getBoolean(INSTALL_SENT_KEY, false)) sendConsentUpdate(configured)
        }
    }

    private fun applyGoogleAdsConsent(
        edit: android.content.SharedPreferences.Editor,
        consent: TrackHubGoogleAdsConsent,
    ): android.content.SharedPreferences.Editor {
        consent.adUserData.booleanValue()?.let { edit.putBoolean(AD_USER_DATA_KEY, it) }
            ?: edit.remove(AD_USER_DATA_KEY)
        consent.adPersonalization.booleanValue()?.let { edit.putBoolean(AD_PERSONALIZATION_KEY, it) }
            ?: edit.remove(AD_PERSONALIZATION_KEY)
        consent.isEea?.let { edit.putBoolean(EEA_KEY, it) } ?: edit.remove(EEA_KEY)
        return edit
    }

    private fun applyPiplConsent(
        edit: android.content.SharedPreferences.Editor,
        consent: TrackHubPiplConsent,
    ): android.content.SharedPreferences.Editor {
        consent.personalInformation.booleanValue()?.let { edit.putBoolean(PIPL_CONSENT_KEY, it) }
            ?: edit.remove(PIPL_CONSENT_KEY)
        consent.crossBorderTransfer.booleanValue()?.let { edit.putBoolean(CROSS_BORDER_TRANSFER_CONSENT_KEY, it) }
            ?: edit.remove(CROSS_BORDER_TRANSFER_CONSENT_KEY)
        consent.adsMeasurement.booleanValue()?.let { edit.putBoolean(ADS_MEASUREMENT_CONSENT_KEY, it) }
            ?: edit.remove(ADS_MEASUREMENT_CONSENT_KEY)
        return edit
    }

    /**
     * Forward the FCM registration token supplied by the host app. TrackHub
     * does not initialize Firebase and does not request notification permission.
     * Call this from FirebaseMessagingService.onNewToken().
     */
    @JvmStatic
    fun setPushToken(context: Context, token: String) {
        if (runtimeCircuitOpen.get()) return
        val value = token.trim()
        if (value.length !in 32..4096) return
        val configured = context.applicationContext
        io.execute {
            if (trackingDisabled || hasPersistedPrivacyDisable(configured)) return@execute
            configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PUSH_TOKEN_KEY, value)
                .apply()
            if (appContext != null) reportPushTokenIfAvailable(configured)
        }
    }

    /** Fetches the durable TrackHub attribution snapshot on the IO executor. */
    @JvmStatic
    fun attribution(completion: (TrackHubAttribution?) -> Unit) {
        if (runtimeCircuitOpen.get()) {
            runHostCallbackOnMain { completion(null) }
            return
        }
        currentAttribution?.let { current ->
            runHostCallbackOnMain { completion(current) }
            return
        }
        io.execute { fetchAttributionIfNeeded(completion) }
    }

    /** Resolves a one-time TrackHub measurement-link deferred path. */
    @JvmStatic
    fun resolveDeferredDeepLink(completion: TrackHubDeferredDeepLinkHandler) {
        if (runtimeCircuitOpen.get()) {
            runHostCallbackOnMain { completion(null) }
            return
        }
        io.execute { resolveDeferredDeepLinkIfNeeded(completion) }
    }

    /**
     * Context overload matching Adjust's Android privacy API. This form is
     * crash-safe even when called before TrackHub.start(). It stops tracking
     * immediately and retries erasure on later foregrounds until TrackHub
     * confirms 200/410.
     */
    @JvmStatic
    @JvmOverloads
    fun gdprForgetMe(
        context: Context,
        reason: String = "user_requested",
        completion: ((Boolean) -> Unit)? = null,
    ) {
        privacyStopRequested.set(true)
        trackingDisabled = true
        currentAttribution = null
        val configuredContext = context.applicationContext
        this.appContext = configuredContext
        val prefs = configuredContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PRIVACY_DISABLED_KEY, false)
            && !hasPendingErasureState(configuredContext)
            && prefs.getString(INSTALL_UID_KEY, null) == null
        ) {
            completion?.let { runHostCallbackOnMain { it(true) } }
            return
        }
        val installUid = loadPendingErasure(configuredContext)?.optString("install_uid")
            ?.takeIf(::isUuid)
            ?: installUid(configuredContext)
        prefs.edit()
            .putBoolean(PRIVACY_DISABLED_KEY, true)
            .commit()
        val job = JSONObject()
            .put("install_uid", installUid)
            .put("reason", reason.take(256))
            .put("attempts", 0)
            .put("next_attempt_at_ms", 0L)
            .put("launch_only", false)
        if (loadPendingErasure(configuredContext) == null
            && !persistPendingErasure(configuredContext, job)
        ) {
            completion?.let { runHostCallbackOnMain { it(false) } }
            return
        }
        addPendingErasureCompletion(completion)
        io.execute {
            clearLocalMeasurementState(configuredContext, keepInstallCredential = true)
            if (!ingestToken.isNullOrBlank()) retryPendingErasure()
        }
    }

    private fun addPendingErasureCompletion(completion: ((Boolean) -> Unit)?) {
        if (completion == null) return
        synchronized(privacyCallbackLock) {
            val previous = pendingErasureCompletion
            pendingErasureCompletion = if (previous == null) completion else { success ->
                previous(success)
                completion(success)
            }
        }
    }

    private fun takePendingErasureCompletion(): ((Boolean) -> Unit)? = synchronized(privacyCallbackLock) {
        val completion = pendingErasureCompletion
        pendingErasureCompletion = null
        completion
    }

    /**
     * Record a non-financial engagement event. Revenue parameters deliberately
     * do not exist; server billing sources remain the financial truth.
     */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(
        name: String,
        callbackParams: Map<String, *> = emptyMap<String, Any>(),
        partnerParams: Map<String, *> = emptyMap<String, Any>(),
    ) {
        if (name.isBlank() || runtimeCircuitOpen.get()) return
        val callbackSnapshot = runCatchingException { callbackParams.toMap() }.getOrNull()
            ?: return log("trackEvent callbackParams could not be copied — skipped")
        val partnerSnapshot = runCatchingException { partnerParams.toMap() }.getOrNull()
            ?: return log("trackEvent partnerParams could not be copied — skipped")
        if (privacyStopRequested.get()) return
        // start() is queued on the same serial executor. An event called
        // immediately after start therefore waits for initialization
        // instead of observing half-configured state or being dropped.
        io.execute {
            if (trackingDisabled || runtimeCircuitOpen.get()) return@execute
            val context = appContext ?: return@execute log("trackEvent before start — skipped")
            val uid = installUid(context)
            val rawBody = runCatchingException {
                val body = deviceContextBody(context)
                    .put("client_event_id", UUID.randomUUID().toString())
                    .put("event_name", name)
                    .put("user_id", uid)
                    .put("occurred_at", iso8601(Date()))
                if (callbackSnapshot.isNotEmpty()) body.put("callback_params", JSONObject(callbackSnapshot))
                if (partnerSnapshot.isNotEmpty()) body.put("partner_params", JSONObject(partnerSnapshot))
                body.toString()
            }.getOrNull()
            if (rawBody == null) {
                log("trackEvent payload is not JSON-serializable — skipped")
                return@execute
            }
            if (rawBody.toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) {
                log("trackEvent payload exceeds ${MAX_REPORT_BYTES} bytes — skipped")
                return@execute
            }
            sendOrQueue("sdk/track", rawBody)
        }
    }

    /** Canonical subscription-funnel event with placement as a parameter. */
    @JvmStatic
    @JvmOverloads
    fun trackSalesEvent(
        event: TrackHubSalesEvent,
        placement: TrackHubSalesPlacement? = null,
        callbackParams: Map<String, *> = emptyMap<String, Any>(),
        partnerParams: Map<String, *> = emptyMap<String, Any>(),
    ) {
        if (runtimeCircuitOpen.get()) return
        val needsPlacement = event != TrackHubSalesEvent.ONBOARDING_SHOWN
        if (needsPlacement && placement == null) {
            log("${event.value} requires a canonical placement — skipped")
            return
        }
        val canonical = runCatchingException { callbackParams.toMutableMap() }.getOrNull()
            ?: return log("sales event parameters could not be copied — skipped")
        if (placement != null) canonical["placement_name"] = placement.value
        trackEvent(event.value, canonical, partnerParams)
    }

    /**
     * Record the device-side observation for a store purchase. This sends no
     * money; the server joins by transaction id and supplies value/currency.
     * The endpoint requires sdkSecret and a real device IP.
     */
    @JvmStatic
    @JvmOverloads
    fun trackPurchaseObserved(transactionId: String, productId: String? = null) {
        if (transactionId.isBlank() || runtimeCircuitOpen.get()) return
        io.execute {
            if (runtimeCircuitOpen.get()) return@execute
            if (sdkSecret.isNullOrEmpty()) return@execute log("trackPurchaseObserved requires sdkSecret — skipped")
            val context = appContext ?: return@execute log("trackPurchaseObserved before start — skipped")
            val uid = installUid(context)
            val body = deviceContextBody(context)
                .put("transaction_id", transactionId)
                .put("user_id", uid)
                .put("install_uid", installUid(context))
            if (!productId.isNullOrEmpty()) body.put("product_id", productId)
            sendOrQueue(
                "sdk/purchase-context",
                body.toString(),
                kind = "transaction_context",
                dedupeKey = "transaction_context:$transactionId",
            )
        }
    }

    /** Capture Google or ChatGPT Ads deep-link ids for the next session reattribution. */
    @JvmStatic
    fun handleDeepLink(uri: Uri): Boolean =
        if (runtimeCircuitOpen.get()) false else captureDeepLink(appContext, uri)

    /**
     * Cold-launch overload that persists click IDs before [start]. Prefer
     * this form from an Activity/Application intent handler.
     */
    @JvmStatic
    fun handleDeepLink(context: Context, uri: Uri): Boolean =
        if (runtimeCircuitOpen.get()) false else captureDeepLink(context.applicationContext, uri)

    private fun captureDeepLink(context: Context?, uri: Uri): Boolean {
        if (trackingDisabled) {
            pendingGclid = null
            pendingGbraid = null
            pendingOpenAiOppref = null
            return false
        }
        // Android throws UnsupportedOperationException when query parameters
        // are read from opaque URIs such as mailto:. Deep-link handling is a
        // public SDK boundary and must always fail open for the host app.
        if (!uri.isHierarchical) return false
        val parameters = runCatchingException {
            Triple(
                uri.getQueryParameter("gclid")?.takeIf { it.isNotEmpty() },
                uri.getQueryParameter("gbraid")?.takeIf { it.isNotEmpty() },
                normalizedOpenAiOppref(uri.getQueryParameter("oppref")),
            )
        }.getOrElse {
            log("unsupported deep link — skipped")
            return false
        }
        val (gclid, gbraid, oppref) = parameters
        if (gclid == null && gbraid == null && oppref == null) return false
        pendingGclid = gclid
        pendingGbraid = gbraid
        pendingOpenAiOppref = oppref
        val hasGoogleReference = gclid != null || gbraid != null
        if (oppref != null && !hasGoogleReference) {
            pendingGclid = null
            pendingGbraid = null
        } else if (hasGoogleReference && oppref == null) {
            pendingOpenAiOppref = null
        }
        context?.let { persistedContext ->
            io.execute {
                if (hasPersistedPrivacyDisable(persistedContext)) return@execute
                val edit = persistedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                if (oppref != null && !hasGoogleReference) {
                    edit
                        .remove(PENDING_GCLID_KEY)
                        .remove(PENDING_GBRAID_KEY)
                        .remove(GCLID_KEY)
                        .remove(GBRAID_KEY)
                } else if (hasGoogleReference && oppref == null) {
                    edit.remove(OPENAI_OPPREF_KEY).remove(PENDING_OPENAI_OPPREF_KEY)
                }
                gclid?.let { edit.putString(PENDING_GCLID_KEY, it).putString(GCLID_KEY, it) }
                gbraid?.let { edit.putString(PENDING_GBRAID_KEY, it).putString(GBRAID_KEY, it) }
                oppref?.let {
                    edit
                        .putString(OPENAI_OPPREF_KEY, it)
                        .putString(PENDING_OPENAI_OPPREF_KEY, it)
                }
                edit.apply()
            }
        }
        appContext?.let { configured -> io.execute { beginSessionIfNeeded(configured, force = true) } }
        return true
    }

    internal fun normalizedOpenAiOppref(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1024 }

    private fun hasPersistedPrivacyDisable(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PRIVACY_DISABLED_KEY, false)

    private fun pendingErasureFile(context: Context): File =
        File(context.noBackupFilesDir, "trackhub-pending-erasure-v2.json")

    private fun hasPendingErasureState(context: Context): Boolean {
        val base = pendingErasureFile(context)
        if (base.exists()) return true
        // AtomicFile can leave a recovery sibling after a process or storage
        // failure. Any such state keeps privacy fail-closed.
        return base.parentFile?.listFiles()?.any { it.name.startsWith(base.name) } == true
    }

    /** Recover pre-3.0 token-scoped privacy jobs even after the app starts with
     * a newly rotated sdkKey. The app sandbox itself is the privacy namespace. */
    private fun migrateLegacyPrivacyState(context: Context) = synchronized(privacyStateLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.all.any { (key, value) ->
                key.startsWith(LEGACY_PRIVACY_DISABLED_PREFIX)
                    && key != PRIVACY_DISABLED_KEY
                    && value == true
            }
        ) {
            prefs.edit().putBoolean(PRIVACY_DISABLED_KEY, true).commit()
        }
        if (loadPendingErasure(context) != null) return

        val fileCandidates = context.noBackupFilesDir.listFiles()?.filter {
            it.name.startsWith("trackhub-pending-erasure-")
                && it.name != "trackhub-pending-erasure-v2.json"
        }.orEmpty()
        for (file in fileCandidates) {
            val job = runCatchingException {
                val raw = file.readText(Charsets.UTF_8)
                if (raw.toByteArray(Charsets.UTF_8).size > 1024) null else JSONObject(raw)
            }.getOrNull()?.takeIf {
                isUuid(it.optString("install_uid")) && it.optString("reason").length <= 256
            } ?: continue
            if (persistPendingErasure(context, job)) {
                runCatchingException { file.delete() }
                prefs.edit().putBoolean(PRIVACY_DISABLED_KEY, true).commit()
                return
            }
        }
        for ((key, value) in prefs.all) {
            if (!key.startsWith(LEGACY_PENDING_ERASURE_PREFIX) || key == PENDING_ERASURE_KEY) continue
            val raw = value as? String ?: continue
            if (raw.toByteArray(Charsets.UTF_8).size > 1024) continue
            val job = runCatchingException { JSONObject(raw) }.getOrNull()?.takeIf {
                isUuid(it.optString("install_uid")) && it.optString("reason").length <= 256
            } ?: continue
            if (persistPendingErasure(context, job)) {
                prefs.edit().remove(key).putBoolean(PRIVACY_DISABLED_KEY, true).commit()
                return
            }
        }
    }

    private fun persistPendingErasure(context: Context, job: JSONObject): Boolean = synchronized(privacyStateLock) {
        val bytes = job.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1024) return@synchronized false
        val atomic = AtomicFile(pendingErasureFile(context))
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.flush()
            atomic.finishWrite(output)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(PENDING_ERASURE_KEY)
                .commit()
            true
        } catch (_: Exception) {
            output?.let { runCatchingException { atomic.failWrite(it) } }
            // Keep a synchronous second copy if AtomicFile cannot be opened.
            // A killed process must still resume the privacy request.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_ERASURE_KEY, job.toString())
                .commit()
        }
    }

    private fun loadPendingErasure(context: Context): JSONObject? = synchronized(privacyStateLock) {
        val atomic = AtomicFile(pendingErasureFile(context))
        val fromFile = runCatchingException {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val raw = reader.readText()
                if (raw.toByteArray(Charsets.UTF_8).size > 1024) return@runCatchingException null
                JSONObject(raw).takeIf {
                    isUuid(it.optString("install_uid")) && it.optString("reason").length <= 256
                }
            }
        }.getOrNull()
        if (fromFile != null) return@synchronized fromFile
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PENDING_ERASURE_KEY, null)
            ?: return@synchronized null
        if (raw.toByteArray(Charsets.UTF_8).size > 1024) return@synchronized null
        runCatchingException { JSONObject(raw) }.getOrNull()?.takeIf {
            isUuid(it.optString("install_uid")) && it.optString("reason").length <= 256
        }
    }

    private fun deletePendingErasure(context: Context) = synchronized(privacyStateLock) {
        runCatchingException { AtomicFile(pendingErasureFile(context)).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PENDING_ERASURE_KEY)
            .commit()
    }

    private fun clearLocalMeasurementState(context: Context, keepInstallCredential: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Delete every production/Test-Lab queue. No pre-erasure event may be
        // delivered after the privacy action, even if a different environment
        // is selected on the next launch.
        context.noBackupFilesDir.listFiles()?.filter {
            it.name.startsWith("trackhub-$PENDING_REPORTS_KEY")
        }?.forEach { runCatchingException { it.delete() } }
        val edit = prefs.edit()
            .remove(PENDING_REPORTS_KEY)
            .remove(PUSH_TOKEN_KEY)
            .remove(ADVERTISING_ID_KEY)
            .remove(APP_SET_ID_KEY)
            .remove(LIMIT_AD_TRACKING_KEY)
            .remove(PENDING_GCLID_KEY)
            .remove(PENDING_GBRAID_KEY)
            .remove(GCLID_KEY)
            .remove(GBRAID_KEY)
            .remove(OPENAI_OPPREF_KEY)
            .remove(PENDING_OPENAI_OPPREF_KEY)
            .remove(DEFERRED_MATCH_TOKEN_KEY)
            .remove(FIRST_OPEN_AT_KEY)
            .remove(SESSION_SEQ_KEY)
            .remove(LAST_BACKGROUND_KEY)
            .remove(COUNTRY_CODE_KEY)
            .remove(AD_USER_DATA_KEY)
            .remove(AD_PERSONALIZATION_KEY)
            .remove(EEA_KEY)
            .remove(PIPL_CONSENT_KEY)
            .remove(CROSS_BORDER_TRANSFER_CONSENT_KEY)
            .remove(ADS_MEASUREMENT_CONSENT_KEY)
            .remove(EXTERNAL_IDENTITIES_KEY)
            .remove(EXTERNAL_IDENTITY_ACK_KEY)
        prefs.all.keys.filter {
            it.startsWith(DEFERRED_RESOLVE_PREFIX) ||
                it.startsWith(INSTALL_CREDENTIAL_BOOTSTRAP_PREFIX)
        }.forEach(edit::remove)
        RETIRED_MEASUREMENT_GEO_KEYS.forEach(edit::remove)
        if (!keepInstallCredential) {
            edit.remove(INSTALL_UID_KEY).remove(INSTALL_SENT_KEY)
        }
        edit.commit()
        pendingGclid = null
        pendingGbraid = null
        pendingOpenAiOppref = null
        firebaseAppInstanceId = null
    }

    /** Runs on the state executor and never re-enables tracking on failure. */
    private fun retryPendingErasure() {
        if (erasureInFlight) return
        val context = appContext ?: return
        val token = ingestToken ?: return
        val networkConfig = currentNetworkConfig() ?: return
        val job = loadPendingErasure(context) ?: return
        val nextAttemptAtMs = job.optLong("next_attempt_at_ms", 0L)
        val delayMs = nextAttemptAtMs - System.currentTimeMillis()
        if (delayMs > 0) {
            if (job.optBoolean("launch_only", false)) return
            scheduleWatchdog("privacy retry", delayMs, TimeUnit.MILLISECONDS) {
                io.execute { retryPendingErasure() }
            }
            return
        }
        val installUid = job.optString("install_uid")
        val body = JSONObject()
            .put("install_uid", installUid)
            .put("reason", job.optString("reason", "user_requested").take(256))
            .put("sdk_name", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
            .toString()
        val installCredential = loadInstallCredential(context, token, installUid)
        erasureInFlight = true
        auxiliaryNetwork.execute {
            var result = if (installCredential != null) {
                postForResponse(networkConfig, "sdk/forget-device", body, installCredential)
            } else {
                postForResponse(networkConfig, "sdk/forget-device/recover", body)
            }
            if (result.first == 401 && installCredential != null) {
                result = postForResponse(networkConfig, "sdk/forget-device/recover", body)
            }
            if (result.first == 401 && applyServerClock(result.second)) {
                result = postForResponse(networkConfig, "sdk/forget-device/recover", body)
            }
            io.execute {
                erasureInFlight = false
                if (result.first in 200..299 || result.first == 410) {
                    deleteAllInstallCredentials(context)
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(PRIVACY_DISABLED_KEY, true)
                        .commit()
                    deletePendingErasure(context)
                    clearLocalMeasurementState(context, keepInstallCredential = false)
                    takePendingErasureCompletion()?.let { callback ->
                        runHostCallbackOnMain { callback(true) }
                    }
                    log("device privacy erasure confirmed")
                    return@execute
                }

                val attempts = job.optInt("attempts", 0).coerceAtLeast(0) + 1
                val retryable = result.first < 0
                    || result.first == 408
                    || result.first == 429
                    || result.first >= 500
                val retryDelayMs = if (retryable) {
                    val exponent = min(attempts - 1, 9)
                    val cap = min(RETRY_MAX_MS, RETRY_BASE_MS * (1L shl exponent))
                    Random.nextLong(cap + 1)
                } else {
                    24 * 60 * 60_000L
                }
                job.put("attempts", attempts)
                    .put("next_attempt_at_ms", System.currentTimeMillis() + retryDelayMs)
                    .put("launch_only", !retryable)
                persistPendingErasure(context, job)
                if (retryable) {
                    scheduleWatchdog("privacy retry", retryDelayMs, TimeUnit.MILLISECONDS) {
                        io.execute { retryPendingErasure() }
                    }
                    log("privacy erasure pending — retrying with backoff")
                } else {
                    log("privacy erasure rejected — retry deferred until a later launch/foreground")
                }
            }
        }
    }

    // MARK: - Sessions

    private fun registerLifecycle(context: Context) {
        if (lifecycleRegistered) return
        val app = context as? Application ?: return
        lifecycleRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = startedActivities == 0
                startedActivities += 1
                if (wasBackground) io.execute {
                    if (trackingDisabled) {
                        retryPendingErasure()
                    } else {
                        if (!reportInstallIfNeeded(context)) beginSessionIfNeeded(context)
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(LAST_BACKGROUND_KEY, System.currentTimeMillis()).apply()
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun beginSessionIfNeeded(context: Context, force: Boolean = false) {
        if (runtimeCircuitOpen.get()) return
        val uid = installUid(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastBackground = prefs.getLong(LAST_BACKGROUND_KEY, 0L)
        if (!force && lastBackground != 0L && now - lastBackground <= SESSION_TIMEOUT_MS) return
        val seq = prefs.getInt(SESSION_SEQ_KEY, 0) + 1
        prefs.edit().putInt(SESSION_SEQ_KEY, seq).putLong(LAST_BACKGROUND_KEY, now).apply()
        val body = deviceContextBody(context)
            .put("user_id", uid)
            .put("session_uid", UUID.randomUUID().toString())
            .put("session_num", seq)
            .put("started_at", iso8601(Date(now)))
        val gclid = pendingGclid ?: prefs.getString(PENDING_GCLID_KEY, null)
        val gbraid = pendingGbraid ?: prefs.getString(PENDING_GBRAID_KEY, null)
        val oppref = pendingOpenAiOppref ?: prefs.getString(PENDING_OPENAI_OPPREF_KEY, null)
        gclid?.let { body.put("gclid", it) }
        gbraid?.let { body.put("gbraid", it) }
        oppref?.let { body.put("oppref", it) }
        if (sendOrQueue("sdk/session", body.toString())) {
            // Clear one-shot sources only after the exact session payload is
            // durable. A full/unwritable queue must not lose attribution.
            prefs.edit()
                .remove(PENDING_GCLID_KEY)
                .remove(PENDING_GBRAID_KEY)
                .remove(PENDING_OPENAI_OPPREF_KEY)
                .apply()
            pendingGclid = null
            pendingGbraid = null
            pendingOpenAiOppref = null
        }
        fetchAttributionIfNeeded()
    }

    // MARK: - Install reporting

    private fun reportInstallIfNeeded(context: Context): Boolean {
        if (trackingDisabled || runtimeCircuitOpen.get()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installUid = installUid(context)
        val installAlreadySent = prefs.getBoolean(INSTALL_SENT_KEY, false)
        val hasCredential = ingestToken?.let {
            loadInstallCredential(context, it, installUid)
        } != null
        val bootstrapKey = installCredentialBootstrapKey(ingestToken ?: return false)
        if (integrationTestToken == null && !shouldReportInstallForCredential(
            installAlreadySent,
            hasCredential,
            prefs.getLong(bootstrapKey, 0L),
            System.currentTimeMillis(),
        )) {
            return false
        }

        val client = InstallReferrerClient.newBuilder(context).build()
        val initialSent = AtomicBoolean(false)
        fun finishInitial(referrer: String?) {
            if (!initialSent.compareAndSet(false, true)) return
            io.execute { sendInstall(context, prefs, referrer) }
        }
        fun finishFromVendor(referrer: String?) {
            if (initialSent.compareAndSet(false, true)) {
                io.execute { sendInstall(context, prefs, referrer) }
            } else if (!referrer.isNullOrBlank()) {
                // The bounded organic fallback may have fired first. Preserve a
                // late Play response as a second, idempotent attribution signal
                // for the same install instead of silently discarding it.
                io.execute {
                    sendInstall(
                        context,
                        prefs,
                        referrer,
                        dedupeKey = "install_referrer",
                        beginSession = false,
                    )
                }
            }
            runCatchingException { client.endConnection() }
        }
        // Some vendor implementations neither connect nor disconnect. A
        // bounded fallback keeps the install/session queue moving as organic.
        scheduleWatchdog("install referrer fallback", 3, TimeUnit.SECONDS) { finishInitial(null) }
        scheduleWatchdog("install referrer cleanup", 30, TimeUnit.SECONDS) {
            runCatchingException { client.endConnection() }
        }
        runCatchingException {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val referrer = runCatchingException {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            client.installReferrer.installReferrer
                        } else null
                    }.getOrNull()
                    finishFromVendor(referrer)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // referrer unavailable — still report the install (organic)
                    finishFromVendor(null)
                }
            })
        }.onFailure {
            log("Install Referrer unavailable — reporting organic install")
            finishFromVendor(null)
        }
        return true
    }

    private fun sendInstall(
        context: Context,
        prefs: android.content.SharedPreferences,
        referrer: String?,
        dedupeKey: String = "install",
        beginSession: Boolean = true,
    ) {
        if (runtimeCircuitOpen.get()) return
        val uid = installUid(context)
        deferredMatchToken(referrer)?.let { matchToken ->
            prefs.edit().putString(DEFERRED_MATCH_TOKEN_KEY, matchToken).apply()
        }
        val body = JSONObject()
            .put("user_id", uid)
            .put("install_uid", installUid(context))
            .put("platform", "android")
            // sdk_* fields inside the signed body so the HMAC authenticates them
            .put("sdk_name", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("occurred_at", iso8601(firstOpenAt(context)))
        explicitCountryCode(prefs)?.let { body.put("country", it) }
        body.put("locale", Locale.getDefault().toString())
        body.put("device_model", Build.MODEL ?: "Android")
        body.put("build", Build.ID ?: "")
        appVersion(context)?.let { body.put("app_version", it) }
        if (!referrer.isNullOrEmpty()) body.put("install_referrer", referrer)
        prefs.getString(GCLID_KEY, null)?.let { body.put("gclid", it) }
        prefs.getString(GBRAID_KEY, null)?.let { body.put("gbraid", it) }
        prefs.getString(OPENAI_OPPREF_KEY, null)?.let { body.put("oppref", it) }
        appendAdvertisingId(context, prefs, body)
        // Firebase app_instance_id (GA4 join key for server-confirmed conversions).
        firebaseAppInstanceId?.takeIf { it.isNotEmpty() }?.let { body.put("app_instance_id", it) }
        appendConsent(prefs, body)

        val production = integrationTestToken == null
        val accepted = sendOrQueue(
            path = "install",
            rawBody = body.toString(),
            kind = when {
                !production -> "test_install"
                dedupeKey == "install" -> "production_install"
                else -> "production_install_referrer"
            },
            dedupeKey = dedupeKey,
        )
        if (accepted && production) {
            ingestToken?.let { token ->
                prefs.edit()
                    .putLong(installCredentialBootstrapKey(token), System.currentTimeMillis())
                    .apply()
            }
        }
        if (accepted && beginSession) beginSessionIfNeeded(context)
    }

    internal fun deferredMatchToken(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        return referrer.split('&').asSequence().mapNotNull { pair ->
            runCatchingException {
                val separator = pair.indexOf('=')
                val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
                if (URLDecoder.decode(rawKey, Charsets.UTF_8.name()) != "trackhub_match_token") {
                    return@runCatchingException null
                }
                val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
                URLDecoder.decode(rawValue, Charsets.UTF_8.name()).takeIf { it.length in 1..128 }
            }.getOrNull()
        }.firstOrNull()
    }

    private fun appendConsent(prefs: android.content.SharedPreferences, body: JSONObject) {
        if (prefs.contains(AD_USER_DATA_KEY)) body.put("ad_user_data", prefs.getBoolean(AD_USER_DATA_KEY, false))
        if (prefs.contains(AD_PERSONALIZATION_KEY)) body.put("ad_personalization", prefs.getBoolean(AD_PERSONALIZATION_KEY, false))
        if (prefs.contains(EEA_KEY)) body.put("eea", prefs.getBoolean(EEA_KEY, false))
        if (prefs.contains(PIPL_CONSENT_KEY)) body.put("pipl_consent", prefs.getBoolean(PIPL_CONSENT_KEY, false))
        if (prefs.contains(CROSS_BORDER_TRANSFER_CONSENT_KEY)) {
            body.put("cross_border_transfer_consent", prefs.getBoolean(CROSS_BORDER_TRANSFER_CONSENT_KEY, false))
        }
        if (prefs.contains(ADS_MEASUREMENT_CONSENT_KEY)) {
            body.put("ads_measurement_consent", prefs.getBoolean(ADS_MEASUREMENT_CONSENT_KEY, false))
        }
    }

    private fun appendAdvertisingId(
        context: Context,
        prefs: android.content.SharedPreferences,
        body: JSONObject,
    ) {
        if (!collectAdvertisingId || !prefs.getBoolean(AD_USER_DATA_KEY, false)) return
        val info = try {
            AdvertisingIdClient.getAdvertisingIdInfo(context)
        } catch (_: Exception) {
            null
        }
        val id = info?.id?.takeIf {
            it.isNotBlank() && it != "00000000-0000-0000-0000-000000000000" && !info.isLimitAdTrackingEnabled
        }
        if (id != null) {
            prefs.edit()
                .putString(ADVERTISING_ID_KEY, id)
                .putBoolean(LIMIT_AD_TRACKING_KEY, false)
                .apply()
            body.put("device_id", id)
            body.put("device_id_type", "advertisingid")
            body.put("limit_ad_tracking", false)
        } else {
            prefs.edit().remove(ADVERTISING_ID_KEY).putBoolean(LIMIT_AD_TRACKING_KEY, true).apply()
            val appSetId = resolveAppSetId(context, prefs)
            if (appSetId != null) {
                val limited = info?.isLimitAdTrackingEnabled
                    ?: !prefs.getBoolean(AD_PERSONALIZATION_KEY, false)
                body.put("device_id", appSetId)
                body.put("device_id_type", "appsetid")
                body.put("limit_ad_tracking", limited)
                prefs.edit().putBoolean(LIMIT_AD_TRACKING_KEY, limited).apply()
            }
        }
    }

    private fun resolveAppSetId(
        context: Context,
        prefs: android.content.SharedPreferences,
    ): String? {
        prefs.getString(APP_SET_ID_KEY, null)?.takeIf(::isUuid)?.let { return it }
        val fetched = try {
            Tasks.await(AppSet.getClient(context).appSetIdInfo, 2, TimeUnit.SECONDS).id
        } catch (_: Exception) {
            null
        }
        val value = fetched?.takeIf(::isUuid) ?: return null
        prefs.edit().putString(APP_SET_ID_KEY, value).apply()
        return value
    }

    private fun isUuid(value: String): Boolean =
        runCatchingException { UUID.fromString(value) }.isSuccess

    private fun sendConsentUpdate(context: Context) {
        val uid = installUid(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val body = JSONObject()
            .put("user_id", uid)
            .put("install_uid", installUid(context))
            .put("platform", "android")
            .put("sdk_name", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
        appendAdvertisingId(context, prefs, body)
        appendConsent(prefs, body)
        sendOrQueue("install", body.toString())
    }

    private fun reportPushTokenIfAvailable(context: Context) {
        if (trackingDisabled || integrationTestToken != null || sdkSecret.isNullOrBlank()) return
        val uid = installUid(context)
        val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PUSH_TOKEN_KEY, null)
            ?.takeIf { it.length in 32..4096 }
            ?: return
        val body = JSONObject()
            .put("user_id", uid)
            .put("install_uid", installUid(context))
            .put("provider", "fcm")
            .put("environment", "production")
            .put("token", token)
            .put("sdk_name", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
        sendOrQueue("sdk/push-token", body.toString())
    }

    // MARK: - Networking

    private fun fetchAttributionIfNeeded(completion: ((TrackHubAttribution?) -> Unit)? = null) {
        if (trackingDisabled || runtimeCircuitOpen.get() || attributionFetchInFlight || integrationTestToken != null) {
            completion?.let { callback ->
                runHostCallbackOnMain { callback(null) }
            }
            return
        }
        val context = appContext
        if (context == null) {
            completion?.let { callback ->
                runHostCallbackOnMain { callback(null) }
            }
            return
        }
        val installUid = installUid(context)
        val directToken = ingestToken?.let { loadInstallCredential(context, it, installUid) }
        if (directToken == null) {
            completion?.let { callback ->
                runHostCallbackOnMain { callback(null) }
            }
            reportInstallIfNeeded(context)
            log("attribution waiting for install credential")
            return
        }
        attributionFetchInFlight = true
        val finished = AtomicBoolean(false)
        fun finish(raw: String?) {
            if (!finished.compareAndSet(false, true)) return
            io.execute {
                attributionFetchInFlight = false
                val snapshot = raw?.let(::parseAttribution)
                if (snapshot != null) {
                    val changed = currentAttribution?.revision != snapshot.revision
                    currentAttribution = snapshot
                    if (changed) attributionChangedHandler?.let { handler ->
                        runHostCallbackOnMain { handler(snapshot) }
                    }
                }
                completion?.let { callback ->
                    runHostCallbackOnMain { callback(snapshot) }
                }
            }
        }
        scheduleWatchdog("attribution timeout", CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) {
            log("attribution fetch timed out")
            finish(null)
        }
        val networkConfig = currentNetworkConfig()
        if (networkConfig == null) {
            finish(null)
            return
        }
        auxiliaryNetwork.execute {
            val (code, raw) = postForResponse(
                networkConfig,
                "sdk/attribution",
                JSONObject().put("install_uid", installUid).toString(),
                directToken,
            )
            if (code == 401) {
                deleteInstallCredential(context, networkConfig.ingestToken, installUid)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(installCredentialBootstrapKey(networkConfig.ingestToken))
                    .apply()
                io.execute { reportInstallIfNeeded(context) }
            }
            finish(raw.takeIf { code in 200..299 })
        }
    }

    private fun parseAttribution(raw: String): TrackHubAttribution? = runCatchingException {
        val envelope = JSONObject(raw)
        if (!envelope.optBoolean("ok")) return@runCatchingException null
        val attribution = envelope.optJSONObject("attribution") ?: return@runCatchingException null
        if (attribution.optString("provider") != "custom") return@runCatchingException null
        val dataJson = attribution.getJSONObject("data")
        val data = buildMap<String, String> {
            val keys = dataJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                dataJson.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }
        TrackHubAttribution(
            revision = attribution.getString("revision"),
            status = data["status"] ?: "unknown",
            network = data["network"] ?: "unknown",
            channel = data["channel"] ?: "unknown",
            campaignId = data["campaign"],
            adGroupId = data["adgroup"],
            keywordId = data["keyword"],
            touchpointKind = data["touchpoint_kind"],
            source = data["attribution_source"],
            data = data,
        )
    }.getOrNull()

    private fun externalIdentityFingerprint(provider: String, userId: String?): String =
        hashKey("$provider\u0000${userId ?: "<logout>"}")

    private fun syncPersistedExternalIdentities(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val desired = runCatchingException {
            JSONObject(prefs.getString(EXTERNAL_IDENTITIES_KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val providers = desired.keys()
        while (providers.hasNext()) {
            val provider = providers.next()
            val stored = desired.optString(provider)
            syncExternalIdentity(context, provider, stored.takeIf { it.isNotEmpty() })
        }
    }

    private fun syncExternalIdentity(context: Context, provider: String, userId: String?) {
        if (trackingDisabled || runtimeCircuitOpen.get() || privacyStopRequested.get()) return
        if (normalizedExternalProvider(provider) != provider) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!shouldEnqueueExternalIdentity(
                installAcknowledged = prefs.getBoolean(INSTALL_SENT_KEY, false),
                integrationTest = integrationTestToken != null,
            )
        ) {
            log("external identity waiting for install acknowledgement")
            return
        }
        val fingerprint = externalIdentityFingerprint(provider, userId)
        val acknowledged = runCatchingException {
            JSONObject(prefs.getString(EXTERNAL_IDENTITY_ACK_KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        if (acknowledged.optString(provider) == fingerprint) return
        val body = JSONObject()
            .put("provider", provider)
            .put("external_user_id", userId ?: JSONObject.NULL)
            .put("install_uid", installUid(context))
            .put("sdk_source", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
            .toString()
        sendOrQueue(
            "sdk/identity",
            body,
            kind = "external_identity",
            dedupeKey = "external_identity:$provider",
        )
    }

    internal fun shouldEnqueueExternalIdentity(
        installAcknowledged: Boolean,
        integrationTest: Boolean,
    ): Boolean = integrationTest || installAcknowledged

    private fun resolveDeferredDeepLinkIfNeeded(
        completion: TrackHubDeferredDeepLinkHandler? = null,
    ) {
        if (trackingDisabled || runtimeCircuitOpen.get() || deferredResolveInFlight || integrationTestToken != null) {
            completion?.let { callback ->
                runHostCallbackOnMain { callback(null) }
            }
            return
        }
        val handler = completion ?: deferredDeepLinkHandler ?: return
        val context = appContext ?: return
        val token = ingestToken ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(INSTALL_SENT_KEY, false)) {
            // Install Referrer is the capability carrier. Do not consume it
            // before the install itself has reached TrackHub. Remember an
            // explicit callback and complete it after the acknowledged send.
            if (completion != null) deferredDeepLinkHandler = completion
            return
        }
        val key = DEFERRED_RESOLVE_PREFIX + hashKey(token)
        if (prefs.getBoolean(key, false)) {
            if (completion != null) runHostCallbackOnMain { handler(null) }
            return
        }
        val matchToken = prefs.getString(DEFERRED_MATCH_TOKEN_KEY, null)
        if (matchToken.isNullOrBlank()) {
            if (completion != null) runHostCallbackOnMain { handler(null) }
            return
        }
        deferredResolveInFlight = true
        val encodedToken = URLEncoder.encode(matchToken, Charsets.UTF_8.name())
        val networkConfig = currentNetworkConfig()
        if (networkConfig == null) {
            deferredResolveInFlight = false
            runHostCallbackOnMain { handler(null) }
            return
        }
        auxiliaryNetwork.execute {
            val (code, raw) = getForResponse(networkConfig, "resolve?match_token=$encodedToken")
            io.execute state@{
                deferredResolveInFlight = false
                if (code !in 200..299 || raw == null) {
                    runHostCallbackOnMain { handler(null) }
                    return@state
                }
                val path = runCatchingException {
                    JSONObject(raw).optString("deep_link_path").takeIf { it.isNotEmpty() }
                }.getOrNull()
                prefs.edit().putBoolean(key, true).remove(DEFERRED_MATCH_TOKEN_KEY).apply()
                runHostCallbackOnMain { handler(path) }
            }
        }
    }

    private fun hashKey(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun deviceContextBody(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val body = JSONObject()
            .put("occurred_at", iso8601(Date()))
            .put("first_open_at", iso8601(firstOpenAt(context)))
            .put("install_uid", installUid(context))
            .put("sdk_source", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("locale", Locale.getDefault().toString())
            .put("device_model", Build.MODEL ?: "Android")
            .put("build", Build.ID ?: "")
        appVersion(context)?.let { body.put("app_version", it) }
        explicitCountryCode(prefs)?.let { body.put("country", it) }
        if (collectAdvertisingId && prefs.getBoolean(AD_USER_DATA_KEY, false)) {
            val advertisingId = prefs.getString(ADVERTISING_ID_KEY, null)?.takeIf { it.isNotBlank() }
            if (advertisingId != null) {
                body.put("device_id", advertisingId)
                body.put("device_id_type", "advertisingid")
                body.put("limit_ad_tracking", prefs.getBoolean(LIMIT_AD_TRACKING_KEY, true))
            } else {
                prefs.getString(APP_SET_ID_KEY, null)?.takeIf(::isUuid)?.let {
                    body.put("device_id", it)
                    body.put("device_id_type", "appsetid")
                    body.put("limit_ad_tracking", prefs.getBoolean(LIMIT_AD_TRACKING_KEY, true))
                }
            }
        }
        return body
    }

    private fun explicitCountryCode(prefs: android.content.SharedPreferences): String? =
        normalizedCountryCode(prefs.getString(COUNTRY_CODE_KEY, null))

    private fun purgeRetiredMeasurementGeographyState(
        context: Context,
        prefs: android.content.SharedPreferences,
    ) {
        val edit = prefs.edit()
        RETIRED_MEASUREMENT_GEO_KEYS.forEach(edit::remove)
        edit.apply()

        val queueKey = pendingReportsKey()
        val items = loadPending(context, queueKey)
        var changed = false
        for (index in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(index) ?: continue
            if (isRetiredMeasurementGeographyReport(
                    kind = item.optString("kind"),
                    dedupeKey = item.optString("dedupe_key"),
                )
            ) {
                items.remove(index)
                changed = true
            }
        }
        if (changed && !persistPending(context, queueKey, items)) {
            log("retired measurement geography refresh could not be removed")
        }
    }

    internal fun isRetiredMeasurementGeographyReport(
        kind: String?,
        dedupeKey: String?,
    ): Boolean = kind == RETIRED_MEASUREMENT_GEO_REFRESH_KIND ||
        dedupeKey == RETIRED_MEASUREMENT_GEO_REFRESH_DEDUPE_KEY

    /**
     * Persist first, then let a single bounded delivery worker drain the queue.
     * This method runs on `io`; a slow network can no longer prevent subsequent
     * events from reaching durable storage.
     */
    private fun sendOrQueue(
        path: String,
        rawBody: String,
        kind: String? = null,
        dedupeKey: String? = null,
    ): Boolean {
        if (trackingDisabled || runtimeCircuitOpen.get()) return false
        val context = appContext ?: return false
        val preparedBody = withIntegrationTestToken(rawBody)
        if (preparedBody.toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) {
            log("$path payload exceeds ${MAX_REPORT_BYTES} bytes — skipped")
            return false
        }
        val queueKey = pendingReportsKey()
        if (!enqueuePending(context, queueKey, path, preparedBody, kind, dedupeKey)) return false
        scheduleNextDelivery(context)
        return true
    }

    private fun enqueuePending(
        context: Context,
        queueKey: String,
        path: String,
        rawBody: String,
        kind: String?,
        dedupeKey: String?,
    ): Boolean {
        val items = loadPending(context, queueKey)
        val now = System.currentTimeMillis()
        var existingIndex = -1
        if (dedupeKey != null) {
            for (i in 0 until items.length()) {
                if (items.optJSONObject(i)?.optString("dedupe_key") == dedupeKey) {
                    existingIndex = i
                    break
                }
            }
        }
        val existing = if (existingIndex >= 0) items.optJSONObject(existingIndex) else null
        val item = JSONObject()
            .put("id", existing?.optString("id")?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString())
            .put("path", path)
            .put("body", rawBody)
            .put("created_at_ms", existing?.optLong("created_at_ms", now) ?: now)
            .put("attempts", 0)
            .put("next_attempt_at_ms", 0L)
        if (kind != null) item.put("kind", kind)
        if (dedupeKey != null) item.put("dedupe_key", dedupeKey)
        if (existingIndex >= 0) items.put(existingIndex, item) else items.put(item)

        trimPending(items)
        val encoded = items.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_PENDING_BYTES) {
            log("offline queue could not be bounded — report skipped")
            return false
        }
        if (!persistPending(context, queueKey, items)) {
            log("offline queue write failed — report skipped")
            openRuntimeCircuit(RuntimeCircuitReason.STORAGE, "offline queue persistence")
            return false
        }
        val itemId = item.getString("id")
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("id") == itemId) return true
        }
        log("offline queue limit reached — oldest report evicted")
        return false
    }

    private fun trimPending(items: JSONArray) {
        while (items.length() > MAX_PENDING_REPORTS) {
            items.remove(pendingEvictionIndex(items))
        }
        val sizes = mutableListOf<Int>()
        for (i in 0 until items.length()) {
            sizes += items.optJSONObject(i).toString().toByteArray(Charsets.UTF_8).size
        }
        var totalBytes = 2 + sizes.sum() + (items.length() - 1).coerceAtLeast(0)
        while (totalBytes > MAX_PENDING_BYTES && items.length() > 0) {
            val index = pendingEvictionIndex(items)
            totalBytes -= sizes[index]
            if (items.length() > 1) totalBytes -= 1
            items.remove(index)
            sizes.removeAt(index)
        }
    }

    private fun pendingEvictionIndex(items: JSONArray): Int {
        for (i in 0 until items.length()) {
            val kind = items.optJSONObject(i)?.optString("kind")
            if (kind != "production_install" && kind != "transaction_context") return i
        }
        return 0
    }

    /**
     * Preserve FIFO except when repairing the SDK 3.0.0 queue ordering bug:
     * the server cannot accept an external identity before its production
     * install, so let that one-shot anchor pass a blocked identity head.
     */
    internal fun preferredPendingDeliveryIndex(kinds: List<String?>): Int {
        if (kinds.isEmpty()) return -1
        if (kinds.first() != "external_identity") return 0
        val installIndex = kinds.indexOfFirst { it == "production_install" }
        return if (installIndex >= 0) installIndex else 0
    }

    private fun pendingQueueFile(context: Context, queueKey: String): File =
        File(context.noBackupFilesDir, "trackhub-$queueKey.json")

    // AtomicFile makes each replacement crash-safe, but its backup-restore
    // protocol is not a cross-thread lock. Serialize readers, writers and
    // deletion so a diagnostic read (or privacy erase) cannot restore an old
    // .bak file while the state executor is committing a newer queue.
    @Synchronized
    private fun loadPending(context: Context, queueKey: String): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = pendingQueueFile(context, queueKey)
        val backup = File(file.path + ".bak")
        val legacyQueueKey = legacyQueueKey(queueKey)
        if (!file.exists() && !backup.exists() && legacyQueueKey != null) {
            val legacyFile = pendingQueueFile(context, legacyQueueKey)
            val legacyBackup = File(legacyFile.path + ".bak")
            if (legacyFile.exists() || legacyBackup.exists()) {
                val migrated = runCatchingException {
                    AtomicFile(legacyFile).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                        decodePending(reader.readText())
                    }
                }.getOrNull()
                if (migrated != null && persistPending(context, queueKey, migrated)) {
                    AtomicFile(legacyFile).delete()
                    prefs.edit().remove(legacyQueueKey).commit()
                    return migrated
                }
                log("legacy offline queue file migration could not be completed")
            }
            if (prefs.contains(legacyQueueKey)) {
                val migrated = decodePending(prefs.getString(legacyQueueKey, "[]") ?: "[]")
                if (migrated != null && persistPending(context, queueKey, migrated)) {
                    prefs.edit().remove(legacyQueueKey).commit()
                    return migrated
                }
            }
        }
        if (!file.exists() && !backup.exists() && prefs.contains(queueKey)) {
            // One-time migration from SDK <= 1.6.0. Delete the legacy value
            // only after AtomicFile has durably committed the same queue.
            val legacy = prefs.getString(queueKey, "[]") ?: "[]"
            val migrated = decodePending(legacy)
            if (migrated != null && persistPending(context, queueKey, migrated)) {
                prefs.edit().remove(queueKey).commit()
                return migrated
            }
            log("legacy offline queue migration could not be completed")
            return migrated ?: JSONArray()
        }
        migrateRotatedQueues(context, queueKey)
        if (!file.exists() && !backup.exists()) return JSONArray()

        val atomic = AtomicFile(file)
        val raw = runCatchingException {
            atomic.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_PENDING_BYTES * 2) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                if (output.size() > MAX_PENDING_BYTES * 2) null
                else output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
        val decoded = raw?.let(::decodePending)
        if (decoded != null) return decoded

        quarantinePendingQueue(file)
        log("offline queue was corrupt or oversized — quarantined")
        return JSONArray()
    }

    /**
     * An sdkKey rotation changes the hashed queue namespace. Merge every queue
     * from the same environment into the active namespace before delivery so
     * already-durable reports cannot be stranded under the previous token.
     */
    private fun migrateRotatedQueues(context: Context, queueKey: String) {
        val marker = "-app-"
        if (!queueKey.contains(marker)) return
        val environmentKey = queueKey.substringBeforeLast(marker)
        val target = pendingQueueFile(context, queueKey)
        val prefix = "trackhub-$environmentKey-app-"
        val candidateBases = context.noBackupFilesDir.listFiles()
            ?.asSequence()
            ?.filter { it.name.startsWith(prefix) && it.name.contains(".json") && !it.name.contains(".corrupt-") }
            ?.map { file -> File(file.path.removeSuffix(".bak")) }
            ?.filter { it.path != target.path }
            ?.distinctBy { it.path }
            ?.toList()
            .orEmpty()
        if (candidateBases.isEmpty()) return

        fun readQueue(file: File): JSONArray? = runCatchingException {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                decodePending(reader.readText())
            }
        }.getOrNull()

        val merged = readQueue(target) ?: JSONArray()
        val ids = mutableSetOf<String>()
        for (index in 0 until merged.length()) {
            merged.optJSONObject(index)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        val migrated = mutableListOf<File>()
        for (candidate in candidateBases) {
            val items = readQueue(candidate) ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty() || ids.add(id)) merged.put(item)
            }
            migrated += candidate
        }
        if (migrated.isEmpty()) return
        trimPending(merged)
        if (!persistPending(context, queueKey, merged)) {
            log("rotated offline queues could not be migrated")
            return
        }
        migrated.forEach { AtomicFile(it).delete() }
        log("rotated offline queues migrated into the active sdkKey namespace")
    }

    private fun decodePending(raw: String): JSONArray? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PENDING_BYTES * 2) return null
        return runCatchingException { JSONArray(raw) }.getOrNull()
    }

    @Synchronized
    private fun persistPending(context: Context, queueKey: String, items: JSONArray): Boolean {
        val bytes = items.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PENDING_BYTES) return false
        val atomic = AtomicFile(pendingQueueFile(context, queueKey))
        var output: FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(bytes)
            output.flush()
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            output?.let { runCatchingException { atomic.failWrite(it) } }
            false
        }
    }

    private fun quarantinePendingQueue(file: File) {
        val suffix = ".corrupt-${System.currentTimeMillis()}"
        val backup = File(file.path + ".bak")
        if (file.exists()) runCatchingException { file.renameTo(File(file.path + suffix)) }
        if (backup.exists()) runCatchingException { backup.renameTo(File(backup.path + suffix)) }
    }

    @Synchronized
    private fun deletePendingQueue(context: Context, queueKey: String) {
        runCatchingException { AtomicFile(pendingQueueFile(context, queueKey)).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(queueKey).commit()
    }

    private fun flushPending(context: Context) {
        scheduleNextDelivery(context)
    }

    // On `io`. Exactly one network request is in flight process-wide.
    private fun scheduleNextDelivery(context: Context) {
        if (trackingDisabled || runtimeCircuitOpen.get() || deliveryInFlight) return
        val queueKey = pendingReportsKey()
        val items = loadPending(context, queueKey)
        while (items.length() > 0) {
            val raw = items.optJSONObject(0)
            val path = raw?.optString("path").orEmpty()
            val body = raw?.optString("body").orEmpty()
            if (raw != null && path.isNotEmpty() && body.isNotEmpty()) break
            items.remove(0)
            persistPending(context, queueKey, items)
        }
        val kinds = (0 until items.length()).map { index ->
            items.optJSONObject(index)?.optString("kind")?.takeIf { it.isNotEmpty() }
        }
        val deliveryIndex = preferredPendingDeliveryIndex(kinds)
        val raw = items.optJSONObject(deliveryIndex) ?: return
        val pending = PendingDelivery(
            id = raw.optString("id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
            path = raw.optString("path"),
            body = raw.optString("body"),
            kind = raw.optString("kind").takeIf { it.isNotEmpty() },
            attempts = raw.optInt("attempts", 0).coerceAtLeast(0),
            nextAttemptAtMs = raw.optLong("next_attempt_at_ms", 0L).coerceAtLeast(0L),
        )
        if (!raw.has("id")) {
            raw.put("id", pending.id)
            persistPending(context, queueKey, items)
        }
        val transientNotBeforeMs = if (deliveryIndex == 0) transientRetryNotBeforeMs else 0L
        val targetAtMs = maxOf(pending.nextAttemptAtMs, transientNotBeforeMs)
        val delayMs = targetAtMs - System.currentTimeMillis()
        if (delayMs > 0) {
            if (retryScheduledAtMs != 0L && retryScheduledAtMs <= targetAtMs) return
            retryGeneration += 1
            val generation = retryGeneration
            retryScheduledAtMs = targetAtMs
            scheduleWatchdog("delivery retry", delayMs, TimeUnit.MILLISECONDS) {
                io.execute {
                    if (retryGeneration != generation) return@execute
                    retryScheduledAtMs = 0L
                    scheduleNextDelivery(context)
                }
            }
            return
        }
        retryGeneration += 1
        retryScheduledAtMs = 0L
        transientRetryNotBeforeMs = 0L
        val networkConfig = currentNetworkConfig() ?: return
        deliveryInFlight = true
        delivery.execute {
            val (code, responseBody) = postForResponse(networkConfig, pending.path, pending.body)
            io.execute {
                deliveryInFlight = false
                handleDeliveryResult(context, queueKey, pending, code, responseBody)
                scheduleNextDelivery(context)
            }
        }
    }

    private fun handleDeliveryResult(
        context: Context,
        queueKey: String,
        pending: PendingDelivery,
        code: Int,
        responseBody: String?,
    ) {
        if (runtimeCircuitOpen.get()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (code == 410 && isServerPrivacyStop(responseBody)) {
            privacyStopRequested.set(true)
            trackingDisabled = true
            prefs.edit().putBoolean(PRIVACY_DISABLED_KEY, true).commit()
            deleteAllInstallCredentials(context)
            deletePendingErasure(context)
            clearLocalMeasurementState(context, keepInstallCredential = false)
            takePendingErasureCompletion()?.let { callback ->
                runHostCallbackOnMain { callback(true) }
            }
            log("server confirmed device privacy erasure — tracking stopped")
            return
        }
        val items = loadPending(context, queueKey)
        var index = -1
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("id") == pending.id) {
                index = i
                break
            }
        }
        if (index < 0) return
        val correctedClock = code == 401 && applyServerClock(responseBody)
        if (correctedClock || isRetryable(code)) {
            val attempts = pending.attempts + 1
            val item = items.getJSONObject(index)
            val delayMs = if (correctedClock && attempts <= 3) {
                1_000L
            } else {
                val exponent = min(attempts - 1, 9)
                val cap = min(RETRY_MAX_MS, RETRY_BASE_MS * (1L shl exponent))
                Random.nextLong(cap + 1)
            }
            val nextAttemptAtMs = System.currentTimeMillis() + delayMs
            item.put("attempts", attempts)
            item.put("next_attempt_at_ms", nextAttemptAtMs)
            // If durable storage is temporarily unavailable, retain an
            // in-process deadline so a write failure cannot create a hot loop.
            transientRetryNotBeforeMs = nextAttemptAtMs
            if (!persistPending(context, queueKey, items)) {
                log("${pending.path} retry state could not be persisted")
                openRuntimeCircuit(RuntimeCircuitReason.STORAGE, "offline retry state persistence")
            }
            log(if (correctedClock) "device clock corrected — retrying ${pending.path}" else "${pending.path} delivery failed — retrying with backoff")
            return
        }

        items.remove(index)
        if (!persistPending(context, queueKey, items)) {
            log("${pending.path} delivery state could not be persisted")
            openRuntimeCircuit(RuntimeCircuitReason.STORAGE, "offline delivery state persistence")
            return
        }
        if (code == 401) {
            openRuntimeCircuit(RuntimeCircuitReason.CREDENTIALS, "SDK credentials rejected")
        }
        if (code in 200..299) {
            when (pending.kind) {
                "production_install" -> {
                    prefs.edit().putBoolean(INSTALL_SENT_KEY, true).commit()
                    saveInstallCredential(context, responseBody)
                    log("install reported")
                    syncPersistedExternalIdentities(context)
                    sendConsentUpdate(context)
                    fetchAttributionIfNeeded()
                    resolveDeferredDeepLinkIfNeeded()
                }
                "production_install_referrer" -> Unit
                "test_install" -> log("integration-test install reported")
                "external_identity" -> {
                    runCatchingException {
                        val body = JSONObject(pending.body)
                        val provider = body.getString("provider")
                        val externalId = if (body.isNull("external_user_id")) {
                            null
                        } else {
                            body.getString("external_user_id")
                        }
                        val acknowledged = JSONObject(
                            prefs.getString(EXTERNAL_IDENTITY_ACK_KEY, "{}") ?: "{}",
                        )
                        acknowledged.put(
                            provider,
                            externalIdentityFingerprint(provider, externalId),
                        )
                        prefs.edit()
                            .putString(EXTERNAL_IDENTITY_ACK_KEY, acknowledged.toString())
                            .apply()
                    }.onFailure { log("external identity ACK could not be persisted") }
                    reportPushTokenIfAvailable(context)
                }
            }
        } else {
            log("${pending.path} rejected with HTTP $code — not retried")
        }
    }

    internal fun isServerPrivacyStop(responseBody: String?): Boolean {
        val body = responseBody?.takeIf { it.length <= 4096 } ?: return false
        if (!body.trim().let { it.startsWith('{') && it.endsWith('}') }) return false
        val error = Regex("\\\"error\\\"\\s*:\\s*\\\"(device_erased|privacy_erased)\\\"")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
        return error == "device_erased" || error == "privacy_erased"
    }

    internal fun offlineQueueNamespace(testToken: String?, appToken: String? = null): String {
        val environment = if (testToken.isNullOrEmpty()) {
            "production"
        } else {
            val digest = MessageDigest.getInstance("SHA-256").digest(testToken.toByteArray(Charsets.UTF_8))
            "test-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        if (appToken.isNullOrBlank()) return environment
        val appDigest = MessageDigest.getInstance("SHA-256").digest(appToken.toByteArray(Charsets.UTF_8))
        val app = appDigest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$environment-app-$app"
    }

    private fun pendingReportsKey(
        testToken: String? = integrationTestToken,
        appToken: String? = ingestToken,
    ): String = "${PENDING_REPORTS_KEY}_${offlineQueueNamespace(testToken, appToken)}"

    private fun legacyQueueKey(queueKey: String): String? {
        val marker = "-app-"
        if (!queueKey.contains(marker)) return null
        val environmentKey = queueKey.substringBeforeLast(marker)
        return if (environmentKey == "${PENDING_REPORTS_KEY}_production") {
            PENDING_REPORTS_KEY
        } else {
            environmentKey
        }
    }

    internal fun offlineQueueCount(context: Context, testToken: String?): Int =
        loadPending(context.applicationContext, pendingReportsKey(testToken)).length()

    internal fun offlineQueuePathCount(
        context: Context,
        testToken: String?,
        path: String,
    ): Int {
        val items = loadPending(context.applicationContext, pendingReportsKey(testToken))
        var count = 0
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("path") == path) count += 1
        }
        return count
    }

    internal fun clearOfflineQueueForTest(context: Context, testToken: String?) {
        deletePendingQueue(context.applicationContext, pendingReportsKey(testToken))
    }

    internal fun hasPendingErasureForTest(context: Context, @Suppress("UNUSED_PARAMETER") token: String): Boolean =
        loadPendingErasure(context.applicationContext) != null

    internal fun isTrackingStoppedForTest(): Boolean =
        trackingDisabled && privacyStopRequested.get()

    private fun withIntegrationTestToken(rawBody: String): String {
        val token = integrationTestToken ?: return rawBody
        return runCatchingException { JSONObject(rawBody).put("test_run_token", token).toString() }
            .getOrDefault(rawBody)
    }

    private fun currentNetworkConfig(): NetworkConfig? {
        val base = endpoint ?: return null
        val token = ingestToken ?: return null
        return NetworkConfig(base, token, sdkSecret)
    }

    // HTTP status, or -1 for a retryable transport failure.
    private fun postForResponse(
        config: NetworkConfig,
        path: String,
        rawBody: String,
        installToken: String? = null,
    ): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val connection = URL("${config.endpoint}/ingest/${config.ingestToken}/$path")
                .openConnection() as HttpURLConnection
            conn = connection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "TrackHub-Android/$SDK_VERSION")
            connection.setRequestProperty("Content-Type", "application/json")
            if (installToken != null) {
                connection.setRequestProperty("X-TrackHub-Install-Token", installToken)
            }

            // SDK Signature over the exact bytes we send
            val secret = config.sdkSecret
            if (!secret.isNullOrEmpty()) {
                val ts = (System.currentTimeMillis() + clockOffsetMs).toString()
                connection.setRequestProperty("X-TrackHub-Timestamp", ts)
                connection.setRequestProperty("X-TrackHub-Signature-Version", "2")
                connection.setRequestProperty(
                    "X-TrackHub-Signature",
                    Signing.sign(secret, ts, config.ingestToken, path, rawBody),
                )
            }

            connection.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            code to readLimitedResponse(connection, code)
        } catch (_: Exception) {
            -1 to null
        } finally {
            runCatchingException { conn?.disconnect() }
        }
    }

    private fun getForResponse(config: NetworkConfig, path: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val connection = URL("${config.endpoint}/ingest/${config.ingestToken}/$path")
                .openConnection() as HttpURLConnection
            conn = connection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "TrackHub-Android/$SDK_VERSION")
            val code = connection.responseCode
            val response = readLimitedResponse(connection, code)
            code to response
        } catch (_: Exception) {
            -1 to null
        } finally {
            runCatchingException { conn?.disconnect() }
        }
    }

    private fun readLimitedResponse(conn: HttpURLConnection, code: Int): String? {
        val stream = (if (code >= 400) conn.errorStream else conn.inputStream) ?: return null
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_RESPONSE_READ_MS)
            while (output.size() < MAX_RESPONSE_BYTES) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                if (remainingMs <= 0) break
                conn.readTimeout = min(10_000L, remainingMs).coerceAtLeast(1L).toInt()
                val remaining = MAX_RESPONSE_BYTES - output.size()
                val read = input.read(buffer, 0, min(buffer.size, remaining))
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun isRetryable(code: Int): Boolean =
        code < 0 || code == 408 || code == 429 || code >= 500

    internal fun serverClockOffset(
        error: String,
        serverTime: Long,
        localTimeMs: Long,
    ): Long? = runCatchingException {
        if (error != "clock_skew" || serverTime !in 1_577_836_800_000L..4_102_444_800_000L) {
            return@runCatchingException null
        }
        Math.subtractExact(serverTime, localTimeMs)
    }.getOrNull()

    private fun serverClockOffset(raw: String?, localTimeMs: Long): Long? = runCatchingException {
        val json = JSONObject(raw ?: return@runCatchingException null)
        val serverTime = json.optLong("server_time_ms", 0L)
        val error = json.optString("error")
        serverClockOffset(error, serverTime, localTimeMs)
    }.getOrNull()

    private fun applyServerClock(raw: String?): Boolean {
        val offset = serverClockOffset(raw, System.currentTimeMillis()) ?: return false
        clockOffsetMs = offset
        return true
    }

    private fun appVersion(context: Context): String? = runCatchingException {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private fun firstOpenAt(context: Context): Date = synchronized(identityStateLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(FIRST_OPEN_AT_KEY, 0L)
        if (stored > 0L) return@synchronized Date(stored)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(FIRST_OPEN_AT_KEY, now).apply()
        Date(now)
    }

    private fun installUid(context: Context): String = synchronized(identityStateLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(INSTALL_UID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALL_UID_KEY, value).apply()
        value
    }

    internal fun isValidInstallCredential(token: String): Boolean =
        Regex("^thic_v1_[A-Za-z0-9_-]{43}$").matches(token)

    internal fun shouldReportInstallForCredential(
        installAlreadySent: Boolean,
        hasCredential: Boolean,
        lastAttemptMs: Long,
        nowMs: Long,
        intervalMs: Long = CREDENTIAL_BOOTSTRAP_INTERVAL_MS,
    ): Boolean {
        if (!installAlreadySent) return true
        if (hasCredential) return false
        return lastAttemptMs <= 0L || nowMs - lastAttemptMs >= intervalMs
    }

    private fun installCredentialFile(context: Context, token: String, installUid: String): File =
        File(context.noBackupFilesDir, "trackhub_install_credential_${hashKey("$token\u0000$installUid")}")

    @Synchronized
    private fun loadInstallCredential(context: Context, token: String, installUid: String): String? {
        val atomic = AtomicFile(installCredentialFile(context, token, installUid))
        return runCatchingException {
            atomic.openRead().use { input ->
                val bytes = ByteArray(129)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 128) return@runCatchingException null
                String(bytes, 0, count, Charsets.UTF_8).takeIf(::isValidInstallCredential)
            }
        }.getOrNull()
    }

    @Synchronized
    private fun storeInstallCredential(
        context: Context,
        token: String,
        installUid: String,
        credential: String,
    ): Boolean {
        if (!isValidInstallCredential(credential)) return false
        val atomic = AtomicFile(installCredentialFile(context, token, installUid))
        var output: FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(credential.toByteArray(Charsets.UTF_8))
            output.flush()
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            output?.let { runCatchingException { atomic.failWrite(it) } }
            false
        }
    }

    private fun deleteInstallCredential(context: Context, token: String, installUid: String) {
        runCatchingException { AtomicFile(installCredentialFile(context, token, installUid)).delete() }
    }

    private fun deleteAllInstallCredentials(context: Context) {
        context.noBackupFilesDir.listFiles()?.filter {
            it.name.startsWith("trackhub_install_credential_")
        }?.forEach { runCatchingException { it.delete() } }
    }

    private fun installCredentialBootstrapKey(token: String): String =
        INSTALL_CREDENTIAL_BOOTSTRAP_PREFIX + hashKey(token)

    private fun saveInstallCredential(context: Context, responseBody: String?) {
        val token = ingestToken ?: return
        val json = runCatchingException { JSONObject(responseBody ?: return) }.getOrNull() ?: return
        val credential = json.optString("install_token")
        val responseInstallUid = json.optString("install_uid")
        if (responseInstallUid != installUid(context)) return
        if (!storeInstallCredential(context, token, responseInstallUid, credential)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(installCredentialBootstrapKey(token))
            .apply()
        log("device-scoped install credential received")
    }

    private fun iso8601(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    /**
     * Contains recoverable failures at every SDK executor boundary. The circuit
     * is process-local: durable reports stay on disk and the next clean app
     * launch retries them. VM-fatal conditions (OOM, stack overflow, thread
     * death) are rethrown because continuing the host process is not safe.
     */
    private fun failSilentExecutor(label: String, delegate: Executor): Executor = Executor { task ->
        try {
            delegate.execute { runFailSilent(label) { task.run() } }
        } catch (failure: Exception) {
            handleAlgorithmFailure(label, failure)
        }
    }

    private inline fun runFailSilent(label: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            handleAlgorithmFailure(label, failure)
        }
    }

    private fun handleAlgorithmFailure(label: String, failure: Exception) {
        val firstOpen = runtimeCircuitOpen.compareAndSet(false, true)
        currentAttribution = null
        if (firstOpen) {
            persistRuntimeCircuitMarker(RuntimeCircuitReason.ALGORITHM)
            // Do not log throwable messages: host-supplied values or vendor
            // details can contain identifiers. Class + boundary are enough.
            log("runtime circuit opened at $label (${failure.javaClass.simpleName}) — measurement disabled until app restart")
        }
    }

    private fun openRuntimeCircuit(reason: RuntimeCircuitReason, label: String) {
        val firstOpen = runtimeCircuitOpen.compareAndSet(false, true)
        currentAttribution = null
        if (firstOpen) {
            persistRuntimeCircuitMarker(reason)
            log("runtime circuit opened at $label — measurement disabled until app restart")
        }
    }

    private fun scheduleWatchdog(
        label: String,
        delay: Long,
        unit: TimeUnit,
        block: () -> Unit,
    ) {
        try {
            watchdog.schedule({ runFailSilent(label, block) }, delay, unit)
        } catch (failure: Exception) {
            handleAlgorithmFailure(label, failure)
        }
    }

    private fun persistRuntimeCircuitMarker(reason: RuntimeCircuitReason) {
        val context = appContext ?: return
        try {
            val marker = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("reason", reason.wireValue)
                .put("occurred_at", iso8601(Date()))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(RUNTIME_CIRCUIT_MARKER_KEY, marker.toString())
                .apply()
        } catch (_: Exception) {
            // Best effort only: persisting the disabled state itself would
            // turn a transient fault into a permanent SDK shutdown.
        }
    }

    private fun reportRuntimeCircuitDiagnosticIfNeeded(context: Context) {
        if (integrationTestToken != null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(RUNTIME_CIRCUIT_MARKER_KEY, null) ?: return
        val marker = try {
            JSONObject(raw)
        } catch (_: Exception) {
            prefs.edit().remove(RUNTIME_CIRCUIT_MARKER_KEY).apply()
            return
        }
        val id = marker.optString("id")
        val reason = marker.optString("reason")
        val occurredAt = marker.optString("occurred_at")
        val validReason = RuntimeCircuitReason.entries.any { it.wireValue == reason }
        val validId = try {
            UUID.fromString(id)
            true
        } catch (_: Exception) {
            false
        }
        if (!validId || !validReason || occurredAt.isBlank()) {
            prefs.edit().remove(RUNTIME_CIRCUIT_MARKER_KEY).apply()
            return
        }
        val body = JSONObject()
            .put("id", id)
            .put("sdk_source", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
            .put("reason", reason)
            .put("occurred_at", occurredAt)
        if (sendOrQueue(
                "sdk/diagnostic",
                body.toString(),
                kind = "sdk_runtime_diagnostic",
                dedupeKey = "sdk_runtime:$id",
            )
        ) {
            prefs.edit().remove(RUNTIME_CIRCUIT_MARKER_KEY).apply()
        }
    }

    internal fun runtimeCircuitOpenForTest(): Boolean = runtimeCircuitOpen.get()

    internal fun awaitStateIdleForTest(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        io.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    internal fun runFailSilentForTest(block: () -> Unit) {
        runFailSilent("test", block)
    }

    internal fun openRuntimeCircuitForTest() {
        openRuntimeCircuit(RuntimeCircuitReason.ALGORITHM, "test")
    }

    internal fun resetRuntimeCircuitForTest() {
        runtimeCircuitOpen.set(false)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(RUNTIME_CIRCUIT_MARKER_KEY)
            ?.commit()
    }

    private fun runOnMain(block: () -> Unit) {
        val looper = try {
            Looper.getMainLooper()
        } catch (failure: Exception) {
            handleAlgorithmFailure("main looper", failure)
            return
        }
        val guarded = Runnable { runFailSilent("main callback", block) }
        if (looper == null) guarded.run() else {
            try {
                Handler(looper).post(guarded)
            } catch (failure: Exception) {
                handleAlgorithmFailure("main callback dispatch", failure)
            }
        }
    }

    // Host callbacks are intentionally outside the SDK circuit. If host code
    // throws, its own crash reporting must see it; treating that as a TrackHub
    // algorithm failure would hide an application bug and disable measurement.
    private fun runHostCallbackOnMain(block: () -> Unit) {
        val looper = try {
            Looper.getMainLooper()
        } catch (failure: Exception) {
            handleAlgorithmFailure("host callback dispatch", failure)
            return
        }
        if (looper == null || Looper.myLooper() == looper) {
            block()
        } else {
            try {
                Handler(looper).post(Runnable { block() })
            } catch (failure: Exception) {
                handleAlgorithmFailure("host callback dispatch", failure)
            }
        }
    }

    private fun log(message: String) {
        // never logs the token or secret
        if (debug) android.util.Log.d("TrackHub", message)
    }
}
