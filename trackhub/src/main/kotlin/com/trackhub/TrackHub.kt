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
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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
typealias ApphudAttributionHandler = (Map<String, String>, (Boolean) -> Unit) -> Unit
typealias ApphudCollectDeviceIdentifiersHandler = () -> Unit
/** Supplies raw TrackHub attribution JSON through the host app's trusted backend. */
typealias TrackHubBackendAttributionProvider = (String, (String?) -> Unit) -> Unit
/** Requests TrackHub privacy erasure through the host app's trusted backend. */
typealias TrackHubBackendPrivacyErasureHandler = (String, String, (Boolean) -> Unit) -> Unit

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
 * privacy-preserving App Conversion purchase bridge. Revenue stays server-side
 * in Apphud; the SDK never accepts a price or currency.
 *
 *  - HTTPS is enforced (token would otherwise leak in transit).
 *  - The ingest token and SDK secret live in memory only — never written to
 *    disk and never logged (debug logging prints status, never credentials).
 *  - Install reports are HMAC-signed when an SDK secret is configured.
 *  - Failed measurement reports use a bounded at-least-once offline queue.
 *  - Purchase observations contain only transaction/product identity; Apphud
 *    supplies the authoritative value/currency before Google delivery.
 *
 * Usage (on app launch, after Apphud starts):
 * ```
 * TrackHub.configure(
 *     context = applicationContext,
 *     endpoint = "https://postbacks.example.com",
 *     ingestToken = "<app ingest token>",
 *     userId = Apphud.userId(),
 *     sdkSecret = "<app sdk secret>" // required for the App Conversion purchase-context bridge
 * )
 * ```
 */
// Queue and privacy mutations run on the dedicated `io` executor. The event
// queue uses AtomicFile; small privacy flags still use synchronous commit().
@SuppressLint("ApplySharedPref")
object TrackHub {

    /** SDK version reported to the platform for integration detection. */
    const val SDK_VERSION = "1.6.1"

    private const val PREFS = "trackhub"
    private const val INSTALL_SENT_KEY = "install_sent"
    private const val FIRST_OPEN_AT_KEY = "first_open_at_ms"
    private const val PENDING_REPORTS_KEY = "pending_reports"
    private const val SESSION_SEQ_KEY = "session_seq"
    private const val LAST_BACKGROUND_KEY = "last_background_ms"
    private const val INSTALL_UID_KEY = "install_uid"
    private const val DEVICE_ID_KEY = "device_id"
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
    private const val PUSH_TOKEN_KEY = "push_token_fcm"
    private const val APPHUD_ATTRIBUTION_REVISION_PREFIX = "apphud_attribution_revision_"
    private const val DEFERRED_RESOLVE_PREFIX = "deferred_resolve_"
    private const val DEFERRED_MATCH_TOKEN_KEY = "deferred_match_token"
    private const val PRIVACY_DISABLED_PREFIX = "privacy_disabled_"
    private const val AD_USER_DATA_KEY = "consent_ad_user_data"
    private const val AD_PERSONALIZATION_KEY = "consent_ad_personalization"
    private const val EEA_KEY = "consent_eea"
    private const val PIPL_CONSENT_KEY = "consent_pipl"
    private const val CROSS_BORDER_TRANSFER_CONSENT_KEY = "consent_cross_border_transfer"
    private const val ADS_MEASUREMENT_CONSENT_KEY = "consent_ads_measurement"
    private const val SESSION_TIMEOUT_MS = 60_000L
    private const val MAX_PENDING_REPORTS = 1000
    private const val MAX_PENDING_BYTES = 4 * 1024 * 1024
    private const val MAX_REPORT_BYTES = 64 * 1024
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_RESPONSE_READ_MS = 15_000L
    private const val CALLBACK_TIMEOUT_MS = 15_000L
    private const val RETRY_BASE_MS = 1_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
    private val io = Executors.newSingleThreadExecutor()
    // Network work must never occupy the state/persistence executor. New
    // events are durably spooled by `io` while this single delivery worker is
    // waiting on an unavailable TrackHub endpoint.
    private val delivery = Executors.newSingleThreadExecutor()
    private val auxiliaryNetwork = Executors.newFixedThreadPool(2)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var endpoint: String? = null
    @Volatile private var ingestToken: String? = null
    @Volatile private var sdkSecret: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var firebaseAppInstanceId: String? = null
    @Volatile private var integrationTestToken: String? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var pendingGclid: String? = null
    @Volatile private var pendingGbraid: String? = null
    @Volatile private var pendingOpenAiOppref: String? = null
    @Volatile private var debug = false
    @Volatile private var lifecycleRegistered = false
    @Volatile private var collectAdvertisingId = false
    @Volatile private var backendAttributionProvider: TrackHubBackendAttributionProvider? = null
    @Volatile private var backendPrivacyErasureHandler: TrackHubBackendPrivacyErasureHandler? = null
    @Volatile private var apphudAttributionHandler: ApphudAttributionHandler? = null
    @Volatile private var apphudCollectDeviceIdentifiersHandler: ApphudCollectDeviceIdentifiersHandler? = null
    @Volatile private var attributionChangedHandler: TrackHubAttributionChangedHandler? = null
    @Volatile private var deferredDeepLinkHandler: TrackHubDeferredDeepLinkHandler? = null
    @Volatile private var currentAttribution: TrackHubAttribution? = null
    @Volatile private var attributionFetchInFlight = false
    @Volatile private var deferredResolveInFlight = false
    @Volatile private var trackingDisabled = false
    // Accessed only from `io`.
    private var deliveryInFlight = false
    private var retryGeneration = 0L
    private var retryScheduledAtMs = 0L
    private var transientRetryNotBeforeMs = 0L
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

    internal fun isAllowedEndpoint(value: String): Boolean {
        if (value != value.trim()) return false
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.trim('[', ']')
            if (uri.isOpaque || uri.userInfo != null || host.isNullOrBlank()) return@runCatching false
            if (uri.rawQuery != null || uri.rawFragment != null) return@runCatching false
            scheme == "https" || (
                scheme == "http" && (
                    host.equals("localhost", ignoreCase = true) ||
                        host == "127.0.0.1" ||
                        host == "::1"
                )
            )
        }.getOrDefault(false)
    }

    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        endpoint: String,
        ingestToken: String,
        userId: String? = null,
        sdkSecret: String? = null,
        firebaseAppInstanceId: String? = null,
        debug: Boolean = false,
        integrationTestToken: String? = null,
        collectAdvertisingId: Boolean = false,
        apphudCollectDeviceIdentifiersHandler: ApphudCollectDeviceIdentifiersHandler? = null,
        backendAttributionProvider: TrackHubBackendAttributionProvider? = null,
        backendPrivacyErasureHandler: TrackHubBackendPrivacyErasureHandler? = null,
        apphudAttributionHandler: ApphudAttributionHandler? = null,
        attributionChangedHandler: TrackHubAttributionChangedHandler? = null,
        deferredDeepLinkHandler: TrackHubDeferredDeepLinkHandler? = null,
        countryCode: String? = null,
    ) {
        // Plaintext HTTP would expose the ingest token and let a MITM poison
        // attribution. localhost only for local development.
        if (!isAllowedEndpoint(endpoint)) {
            log("refusing non-HTTPS endpoint — SDK not configured")
            return
        }
        val configuredAppContext = context.applicationContext
        val prefs = configuredAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hasPersistedPrivacyDisable(configuredAppContext, ingestToken)) {
            this.appContext = configuredAppContext
            this.ingestToken = ingestToken
            trackingDisabled = true
            this.userId = null
            this.firebaseAppInstanceId = null
            pendingGclid = null
            pendingGbraid = null
            pendingOpenAiOppref = null
            prefs.edit()
                .remove(PENDING_GCLID_KEY)
                .remove(PENDING_GBRAID_KEY)
                .remove(GCLID_KEY)
                .remove(GBRAID_KEY)
                .remove(OPENAI_OPPREF_KEY)
                .remove(PENDING_OPENAI_OPPREF_KEY)
                .apply()
            log("tracking disabled after a forget-device request")
            return
        }
        this.endpoint = endpoint.trimEnd('/')
        this.ingestToken = ingestToken
        this.sdkSecret = sdkSecret
        this.userId = userId?.takeIf { it.isNotBlank() } ?: persistentDeviceId(configuredAppContext)
        if (!firebaseAppInstanceId.isNullOrEmpty()) this.firebaseAppInstanceId = firebaseAppInstanceId
        this.integrationTestToken = integrationTestToken?.trim()?.takeIf { it.length in 20..128 }
        this.collectAdvertisingId = collectAdvertisingId
        this.apphudCollectDeviceIdentifiersHandler = apphudCollectDeviceIdentifiersHandler
        this.backendAttributionProvider = backendAttributionProvider
        this.backendPrivacyErasureHandler = backendPrivacyErasureHandler
        this.apphudAttributionHandler = apphudAttributionHandler
        this.attributionChangedHandler = attributionChangedHandler
        this.deferredDeepLinkHandler = deferredDeepLinkHandler
        this.debug = debug

        this.appContext = configuredAppContext
        val prefsEdit = prefs.edit()
        normalizedCountryCode(countryCode)?.let { prefsEdit.putString(COUNTRY_CODE_KEY, it) }
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
        trackingDisabled = false
        firstOpenAt(configuredAppContext)
        registerLifecycle(configuredAppContext)
        apphudCollectDeviceIdentifiersHandler?.let { handler ->
            runOnMain {
                runCatching { handler() }
                    .onFailure { log("Apphud device-identifier handler failed") }
            }
        }
        io.execute {
            reportInstallIfNeeded(configuredAppContext)
            reportPushTokenIfAvailable(configuredAppContext)
            beginSessionIfNeeded(configuredAppContext)
            flushPending(configuredAppContext)
            fetchAttributionIfNeeded()
            resolveDeferredDeepLinkIfNeeded()
        }
    }

    /**
     * Provide the Firebase `app_instance_id` (from
     * `FirebaseAnalytics.getAppInstanceId()`), the GA4/Firebase join key. TrackHub
     * persists it on the install and stamps it on forwarded conversions so a
     * server-confirmed subscription attributes to the right install/campaign.
     * TrackHub does NOT depend on Firebase — the host passes the id in. Call
     * BEFORE [configure], or pass it via `configure(firebaseAppInstanceId = ...)`.
     */
    @JvmStatic
    fun setFirebaseAppInstanceId(appInstanceId: String) {
        if (!trackingDisabled && appInstanceId.isNotEmpty()) firebaseAppInstanceId = appInstanceId
    }

    /**
     * Set the actual ISO-3166 country where measurement originates. Device
     * language/Locale is intentionally not used as geography. A trusted server
     * edge may override this value from its geo header.
     */
    @JvmStatic
    fun setCountryCode(context: Context, countryCode: String) {
        if (trackingDisabled || hasPersistedPrivacyDisable(context.applicationContext)) return
        val value = normalizedCountryCode(countryCode) ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(COUNTRY_CODE_KEY, value).apply()
    }

    internal fun normalizedCountryCode(raw: String?): String? {
        val value = raw?.trim()?.uppercase(Locale.US) ?: return null
        return value.takeIf { it.length == 2 && it != "XX" && it.all { c -> c in 'A'..'Z' } }
    }

    /** Persist Consent Mode signals and re-report them when already configured. */
    @JvmStatic
    fun setGoogleAdsConsent(
        context: Context,
        adUserData: Boolean,
        adPersonalization: Boolean,
        eea: Boolean,
    ) {
        if (trackingDisabled || hasPersistedPrivacyDisable(context.applicationContext)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AD_USER_DATA_KEY, adUserData)
            .putBoolean(AD_PERSONALIZATION_KEY, adPersonalization)
            .putBoolean(EEA_KEY, eea)
            .apply()
        appContext?.let { configured ->
            val prefs = configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(INSTALL_SENT_KEY, false)) io.execute { sendConsentUpdate(configured) }
        }
    }

    /** Persist mainland-China PIPL signals and re-report them when configured. */
    @JvmStatic
    fun setPiplConsent(
        context: Context,
        piplConsent: Boolean,
        crossBorderTransferConsent: Boolean,
        adsMeasurementConsent: Boolean,
    ) {
        if (trackingDisabled || hasPersistedPrivacyDisable(context.applicationContext)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PIPL_CONSENT_KEY, piplConsent)
            .putBoolean(CROSS_BORDER_TRANSFER_CONSENT_KEY, crossBorderTransferConsent)
            .putBoolean(ADS_MEASUREMENT_CONSENT_KEY, adsMeasurementConsent)
            .apply()
        appContext?.let { configured ->
            val prefs = configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(INSTALL_SENT_KEY, false)) io.execute { sendConsentUpdate(configured) }
        }
    }

    /** Update the identity after Apphud resolves it. */
    @JvmStatic
    fun setUserId(userId: String) {
        if (!trackingDisabled && userId.isNotEmpty()) {
            this.userId = userId
            io.execute {
                appContext?.let { reportPushTokenIfAvailable(it) }
                fetchAttributionIfNeeded()
            }
        }
    }

    /**
     * Forward the FCM registration token supplied by the host app. TrackHub
     * does not initialize Firebase and does not request notification permission.
     * Call this from FirebaseMessagingService.onNewToken().
     */
    @JvmStatic
    fun setPushToken(context: Context, token: String) {
        if (trackingDisabled || hasPersistedPrivacyDisable(context.applicationContext)) return
        val value = token.trim()
        if (value.length !in 32..4096) return
        val configured = context.applicationContext
        configured.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PUSH_TOKEN_KEY, value)
            .apply()
        if (appContext != null) io.execute { reportPushTokenIfAvailable(configured) }
    }

    /** Fetches the durable TrackHub attribution snapshot on the IO executor. */
    @JvmStatic
    fun getAttribution(completion: (TrackHubAttribution?) -> Unit) {
        currentAttribution?.let { current ->
            runOnMainSafely("attribution completion") { completion(current) }
            return
        }
        io.execute { fetchAttributionIfNeeded(completion) }
    }

    /** Retries the TrackHub → Apphud custom-attribution bridge. */
    @JvmStatic
    fun refreshApphudAttribution() {
        io.execute { fetchAttributionIfNeeded() }
    }

    /** Resolves a one-time TrackHub measurement-link deferred path. */
    @JvmStatic
    fun resolveDeferredDeepLink(completion: TrackHubDeferredDeepLinkHandler) {
        io.execute { resolveDeferredDeepLinkIfNeeded(completion) }
    }

    /**
     * Erases the app/user identity through the host app's trusted backend and
     * disables SDK delivery only after that backend confirms acceptance.
     */
    @JvmStatic
    @JvmOverloads
    fun forgetDevice(reason: String = "user_requested", completion: ((Boolean) -> Unit)? = null) {
        io.execute {
            val context = appContext
            val uid = userId
            val token = ingestToken
            val handler = backendPrivacyErasureHandler
            if (context == null || uid.isNullOrBlank() || token == null || handler == null) {
                log("forget-device requires backendPrivacyErasureHandler")
                completion?.let { callback ->
                    runOnMainSafely("forget-device completion") { callback(false) }
                }
                return@execute
            }
            val finished = AtomicBoolean(false)
            fun finish(accepted: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                io.execute {
                    if (accepted) {
                        trackingDisabled = true
                        currentAttribution = null
                        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        deletePendingQueue(context, pendingReportsKey())
                        prefs.edit()
                            .putBoolean(PRIVACY_DISABLED_PREFIX + hashKey(token), true)
                            .remove(pendingReportsKey())
                            .remove(PUSH_TOKEN_KEY)
                            .remove(ADVERTISING_ID_KEY)
                            .remove(APP_SET_ID_KEY)
                            .remove(PENDING_GCLID_KEY)
                            .remove(PENDING_GBRAID_KEY)
                            .remove(GCLID_KEY)
                            .remove(GBRAID_KEY)
                            .remove(OPENAI_OPPREF_KEY)
                            .remove(PENDING_OPENAI_OPPREF_KEY)
                            .remove(DEFERRED_MATCH_TOKEN_KEY)
                            .remove(DEVICE_ID_KEY)
                            .remove(INSTALL_UID_KEY)
                            .commit()
                        pendingGclid = null
                        pendingGbraid = null
                        pendingOpenAiOppref = null
                        userId = null
                        firebaseAppInstanceId = null
                    }
                    completion?.let { callback ->
                        runOnMain {
                            runCatching { callback(accepted) }
                                .onFailure { log("forget-device completion failed") }
                        }
                    }
                }
            }
            watchdog.schedule({
                log("forget-device backend timed out")
                finish(false)
            }, CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            runOnMain {
                runCatching {
                    handler(uid, reason.take(256)) { accepted -> finish(accepted) }
                }.onFailure {
                    log("forget-device backend handler failed")
                    finish(false)
                }
            }
        }
    }

    /**
     * Record a non-financial engagement event. Revenue parameters deliberately
     * do not exist; Apphud/S2S remains the financial source of truth.
     */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(
        name: String,
        callbackParams: Map<String, *> = emptyMap<String, Any>(),
        partnerParams: Map<String, *> = emptyMap<String, Any>(),
    ) {
        if (name.isBlank()) return
        val context = appContext ?: return log("trackEvent before configure — skipped")
        val uid = userId ?: return log("trackEvent before configure — skipped")
        val rawBody = runCatching {
            val body = deviceContextBody(context)
                .put("client_event_id", UUID.randomUUID().toString())
                .put("event_name", name)
                .put("user_id", uid)
                .put("occurred_at", iso8601(Date()))
            if (callbackParams.isNotEmpty()) body.put("callback_params", JSONObject(callbackParams))
            if (partnerParams.isNotEmpty()) body.put("partner_params", JSONObject(partnerParams))
            body.toString()
        }.getOrNull()
        if (rawBody == null) {
            log("trackEvent payload is not JSON-serializable — skipped")
            return
        }
        if (rawBody.toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) {
            log("trackEvent payload exceeds ${MAX_REPORT_BYTES} bytes — skipped")
            return
        }
        io.execute { sendOrQueue("sdk/track", rawBody) }
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
        val needsPlacement = event != TrackHubSalesEvent.ONBOARDING_SHOWN
        if (needsPlacement && placement == null) {
            log("${event.value} requires a canonical placement — skipped")
            return
        }
        val canonical = callbackParams.toMutableMap()
        if (placement != null) canonical["placement_name"] = placement.value
        trackEvent(event.value, canonical, partnerParams)
    }

    /**
     * Record the device-side observation for an Apphud purchase. This sends no
     * money; the server joins by transaction id and uses Apphud value/currency.
     * The endpoint requires sdkSecret and a real device IP.
     */
    @JvmStatic
    @JvmOverloads
    fun trackPurchaseObserved(transactionId: String, productId: String? = null) {
        if (transactionId.isBlank()) return
        if (sdkSecret.isNullOrEmpty()) return log("trackPurchaseObserved requires sdkSecret — skipped")
        val context = appContext ?: return log("trackPurchaseObserved before configure — skipped")
        val uid = userId ?: return log("trackPurchaseObserved before configure — skipped")
        val body = deviceContextBody(context)
            .put("transaction_id", transactionId)
            .put("user_id", uid)
        if (!productId.isNullOrEmpty()) body.put("product_id", productId)
        io.execute { sendOrQueue("sdk/purchase-context", body.toString()) }
    }

    /** Capture Google or ChatGPT Ads deep-link ids for the next session reattribution. */
    @JvmStatic
    fun handleDeepLink(uri: Uri): Boolean = captureDeepLink(appContext, uri)

    /**
     * Cold-launch overload that persists click IDs before [configure]. Prefer
     * this form from an Activity/Application intent handler.
     */
    @JvmStatic
    fun handleDeepLink(context: Context, uri: Uri): Boolean =
        captureDeepLink(context.applicationContext, uri)

    private fun captureDeepLink(context: Context?, uri: Uri): Boolean {
        if (trackingDisabled || (context != null && hasPersistedPrivacyDisable(context))) {
            pendingGclid = null
            pendingGbraid = null
            pendingOpenAiOppref = null
            return false
        }
        // Android throws UnsupportedOperationException when query parameters
        // are read from opaque URIs such as mailto:. Deep-link handling is a
        // public SDK boundary and must always fail open for the host app.
        if (!uri.isHierarchical) return false
        val parameters = runCatching {
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
        context?.let { persistedContext ->
            val edit = persistedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            val hasGoogleReference = gclid != null || gbraid != null
            if (oppref != null && !hasGoogleReference) {
                edit
                    .remove(PENDING_GCLID_KEY)
                    .remove(PENDING_GBRAID_KEY)
                    .remove(GCLID_KEY)
                    .remove(GBRAID_KEY)
                pendingGclid = null
                pendingGbraid = null
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
        appContext?.let { configured -> io.execute { beginSessionIfNeeded(configured, force = true) } }
        return true
    }

    internal fun normalizedOpenAiOppref(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1024 }

    private fun hasPersistedPrivacyDisable(
        context: Context,
        token: String? = ingestToken,
    ): Boolean {
        val scopedToken = token?.takeIf { it.isNotBlank() } ?: return trackingDisabled
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PRIVACY_DISABLED_PREFIX + hashKey(scopedToken), false)
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
                if (wasBackground) io.execute { beginSessionIfNeeded(context) }
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
        val uid = userId ?: return
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

    private fun reportInstallIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (integrationTestToken == null && prefs.getBoolean(INSTALL_SENT_KEY, false)) return

        val client = InstallReferrerClient.newBuilder(context).build()
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val referrer = runCatching {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            client.installReferrer.installReferrer
                        } else null
                    }.getOrNull()
                    runCatching { client.endConnection() }
                    io.execute { sendInstall(context, prefs, referrer) }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // referrer unavailable — still report the install (organic)
                    io.execute { sendInstall(context, prefs, null) }
                }
            })
        }.onFailure {
            runCatching { client.endConnection() }
            log("Install Referrer unavailable — reporting organic install")
            io.execute { sendInstall(context, prefs, null) }
        }
    }

    private fun sendInstall(context: Context, prefs: android.content.SharedPreferences, referrer: String?) {
        val uid = userId ?: return
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
        sendOrQueue(
            path = "install",
            rawBody = body.toString(),
            kind = if (production) "production_install" else "test_install",
            dedupeKey = "install",
        )
        if (integrationTestToken == null) resolveDeferredDeepLinkIfNeeded()
    }

    internal fun deferredMatchToken(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        return referrer.split('&').asSequence().mapNotNull { pair ->
            runCatching {
                val separator = pair.indexOf('=')
                val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
                if (URLDecoder.decode(rawKey, Charsets.UTF_8.name()) != "trackhub_match_token") {
                    return@runCatching null
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
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
            null
        }
        val value = fetched?.takeIf(::isUuid) ?: return null
        prefs.edit().putString(APP_SET_ID_KEY, value).apply()
        return value
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess

    private fun sendConsentUpdate(context: Context) {
        val uid = userId ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val body = JSONObject()
            .put("user_id", uid)
            .put("platform", "android")
            .put("sdk_name", "trackhub-android")
            .put("sdk_version", SDK_VERSION)
        appendAdvertisingId(context, prefs, body)
        appendConsent(prefs, body)
        sendOrQueue("install", body.toString())
    }

    private fun reportPushTokenIfAvailable(context: Context) {
        if (trackingDisabled || integrationTestToken != null || sdkSecret.isNullOrBlank()) return
        val uid = userId ?: return
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
        if (trackingDisabled || attributionFetchInFlight || integrationTestToken != null) {
            completion?.let { callback ->
                runOnMainSafely("attribution completion") { callback(null) }
            }
            return
        }
        val uid = userId
        val provider = backendAttributionProvider
        if (uid.isNullOrBlank() || provider == null) {
            completion?.let { callback ->
                runOnMainSafely("attribution completion") { callback(null) }
            }
            if (provider == null) log("attribution fetch requires backendAttributionProvider")
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
                        runOnMain {
                            runCatching { handler(snapshot) }
                                .onFailure { log("attribution-changed handler failed") }
                        }
                    }
                    deliverAttributionToApphud(uid, snapshot)
                }
                completion?.let { callback ->
                    runOnMain {
                        runCatching { callback(snapshot) }
                            .onFailure { log("attribution completion failed") }
                    }
                }
            }
        }
        watchdog.schedule({
            log("backend attribution fetch timed out")
            finish(null)
        }, CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runOnMain {
            runCatching { provider(uid) { raw -> finish(raw) } }
                .onFailure {
                    log("backend attribution provider failed")
                    finish(null)
                }
        }
    }

    private fun parseAttribution(raw: String): TrackHubAttribution? = runCatching {
        val envelope = JSONObject(raw)
        if (!envelope.optBoolean("ok")) return@runCatching null
        val attribution = envelope.optJSONObject("attribution") ?: return@runCatching null
        if (attribution.optString("provider") != "custom") return@runCatching null
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

    private fun deliverAttributionToApphud(userId: String, snapshot: TrackHubAttribution) {
        val handler = apphudAttributionHandler ?: return
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = APPHUD_ATTRIBUTION_REVISION_PREFIX + hashKey(userId)
        if (prefs.getString(key, null) == snapshot.revision) return
        runOnMain {
            runCatching {
                handler(snapshot.data) { accepted ->
                    if (accepted) prefs.edit().putString(key, snapshot.revision).apply()
                }
            }.onFailure { log("Apphud attribution handler failed") }
        }
    }

    private fun resolveDeferredDeepLinkIfNeeded(
        completion: TrackHubDeferredDeepLinkHandler? = null,
    ) {
        if (trackingDisabled || deferredResolveInFlight || integrationTestToken != null) {
            completion?.let { callback ->
                runOnMainSafely("deferred deep-link completion") { callback(null) }
            }
            return
        }
        val handler = completion ?: deferredDeepLinkHandler ?: return
        val context = appContext ?: return
        val token = ingestToken ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DEFERRED_RESOLVE_PREFIX + hashKey(token)
        if (prefs.getBoolean(key, false)) {
            if (completion != null) runOnMainSafely("deferred deep-link handler") { handler(null) }
            return
        }
        val matchToken = prefs.getString(DEFERRED_MATCH_TOKEN_KEY, null)
        if (matchToken.isNullOrBlank()) {
            if (completion != null) runOnMainSafely("deferred deep-link handler") { handler(null) }
            return
        }
        deferredResolveInFlight = true
        val encodedToken = URLEncoder.encode(matchToken, Charsets.UTF_8.name())
        val networkConfig = currentNetworkConfig()
        if (networkConfig == null) {
            deferredResolveInFlight = false
            runOnMain { runCatching { handler(null) } }
            return
        }
        auxiliaryNetwork.execute {
            val (code, raw) = getForResponse(networkConfig, "resolve?match_token=$encodedToken")
            io.execute state@{
                deferredResolveInFlight = false
                if (code !in 200..299 || raw == null) {
                    runOnMain {
                        runCatching { handler(null) }
                            .onFailure { log("deferred deep-link handler failed") }
                    }
                    return@state
                }
                val path = runCatching {
                    JSONObject(raw).optString("deep_link_path").takeIf { it.isNotEmpty() }
                }.getOrNull()
                prefs.edit().putBoolean(key, true).remove(DEFERRED_MATCH_TOKEN_KEY).apply()
                runOnMain {
                    runCatching { handler(path) }
                        .onFailure { log("deferred deep-link handler failed") }
                }
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
        if (trackingDisabled) return false
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
            if (items.optJSONObject(i)?.optString("kind") != "production_install") return i
        }
        return 0
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
        if (!file.exists() && !backup.exists()) return JSONArray()

        val atomic = AtomicFile(file)
        val raw = runCatching {
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

    private fun decodePending(raw: String): JSONArray? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PENDING_BYTES * 2) return null
        return runCatching { JSONArray(raw) }.getOrNull()
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
        } catch (_: Throwable) {
            output?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    private fun quarantinePendingQueue(file: File) {
        val suffix = ".corrupt-${System.currentTimeMillis()}"
        val backup = File(file.path + ".bak")
        if (file.exists()) runCatching { file.renameTo(File(file.path + suffix)) }
        if (backup.exists()) runCatching { backup.renameTo(File(backup.path + suffix)) }
    }

    @Synchronized
    private fun deletePendingQueue(context: Context, queueKey: String) {
        runCatching { AtomicFile(pendingQueueFile(context, queueKey)).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(queueKey).commit()
    }

    private fun flushPending(context: Context) {
        scheduleNextDelivery(context)
    }

    // On `io`. Exactly one network request is in flight process-wide.
    private fun scheduleNextDelivery(context: Context) {
        if (trackingDisabled || deliveryInFlight) return
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
        val raw = items.optJSONObject(0) ?: return
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
        val targetAtMs = maxOf(pending.nextAttemptAtMs, transientRetryNotBeforeMs)
        val delayMs = targetAtMs - System.currentTimeMillis()
        if (delayMs > 0) {
            if (retryScheduledAtMs != 0L && retryScheduledAtMs <= targetAtMs) return
            retryGeneration += 1
            val generation = retryGeneration
            retryScheduledAtMs = targetAtMs
            watchdog.schedule({
                io.execute {
                    if (retryGeneration != generation) return@execute
                    retryScheduledAtMs = 0L
                    scheduleNextDelivery(context)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            return
        }
        retryGeneration += 1
        retryScheduledAtMs = 0L
        transientRetryNotBeforeMs = 0L
        val networkConfig = currentNetworkConfig() ?: return
        deliveryInFlight = true
        delivery.execute {
            val code = postForResponse(networkConfig, pending.path, pending.body).first
            io.execute {
                deliveryInFlight = false
                handleDeliveryResult(context, queueKey, pending, code)
                scheduleNextDelivery(context)
            }
        }
    }

    private fun handleDeliveryResult(
        context: Context,
        queueKey: String,
        pending: PendingDelivery,
        code: Int,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = loadPending(context, queueKey)
        var index = -1
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("id") == pending.id) {
                index = i
                break
            }
        }
        if (index < 0) return
        if (isRetryable(code)) {
            val item = items.getJSONObject(index)
            val attempts = pending.attempts + 1
            val exponent = min(attempts - 1, 9)
            val cap = min(RETRY_MAX_MS, RETRY_BASE_MS * (1L shl exponent))
            val half = (cap / 2).coerceAtLeast(1L)
            val delayMs = half + Random.nextLong(half + 1)
            val nextAttemptAtMs = System.currentTimeMillis() + delayMs
            item.put("attempts", attempts)
            item.put("next_attempt_at_ms", nextAttemptAtMs)
            // If durable storage is temporarily unavailable, retain an
            // in-process deadline so a write failure cannot create a hot loop.
            transientRetryNotBeforeMs = nextAttemptAtMs
            if (!persistPending(context, queueKey, items)) {
                log("${pending.path} retry state could not be persisted")
            }
            log("${pending.path} delivery failed — retrying with backoff")
            return
        }

        items.remove(index)
        if (!persistPending(context, queueKey, items)) {
            log("${pending.path} delivery state could not be persisted")
            return
        }
        if (code in 200..299) {
            when (pending.kind) {
                "production_install" -> {
                    prefs.edit().putBoolean(INSTALL_SENT_KEY, true).commit()
                    log("install reported")
                    sendConsentUpdate(context)
                    fetchAttributionIfNeeded()
                }
                "test_install" -> log("integration-test install reported")
            }
        } else {
            log("${pending.path} rejected with HTTP $code — not retried")
        }
    }

    internal fun offlineQueueNamespace(testToken: String?): String {
        if (testToken.isNullOrEmpty()) return "production"
        val digest = MessageDigest.getInstance("SHA-256").digest(testToken.toByteArray(Charsets.UTF_8))
        return "test-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun pendingReportsKey(testToken: String? = integrationTestToken): String {
        val namespace = offlineQueueNamespace(testToken)
        // Keep the historical production key so an SDK update does not strand
        // real reports already buffered by an older version.
        return if (namespace == "production") PENDING_REPORTS_KEY else "${PENDING_REPORTS_KEY}_$namespace"
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

    private fun withIntegrationTestToken(rawBody: String): String {
        val token = integrationTestToken ?: return rawBody
        return runCatching { JSONObject(rawBody).put("test_run_token", token).toString() }
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

            // SDK Signature over the exact bytes we send
            val secret = config.sdkSecret
            if (!secret.isNullOrEmpty()) {
                val ts = System.currentTimeMillis().toString()
                connection.setRequestProperty("X-TrackHub-Timestamp", ts)
                connection.setRequestProperty("X-TrackHub-Signature-Version", "2")
                connection.setRequestProperty(
                    "X-TrackHub-Signature",
                    Signing.sign(secret, ts, config.ingestToken, path, rawBody),
                )
            }

            connection.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            // Delivery only needs the status. Do not buffer an untrusted or
            // accidentally huge response body inside the host application.
            code to null
        } catch (_: Throwable) {
            -1 to null
        } finally {
            runCatching { conn?.disconnect() }
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
        } catch (_: Throwable) {
            -1 to null
        } finally {
            runCatching { conn?.disconnect() }
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

    private fun appVersion(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    @Synchronized
    private fun firstOpenAt(context: Context): Date {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(FIRST_OPEN_AT_KEY, 0L)
        if (stored > 0L) return Date(stored)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(FIRST_OPEN_AT_KEY, now).apply()
        return Date(now)
    }

    @Synchronized
    private fun persistentDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(DEVICE_ID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(DEVICE_ID_KEY, value).apply()
        return value
    }

    @Synchronized
    private fun installUid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(INSTALL_UID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALL_UID_KEY, value).apply()
        return value
    }

    private fun iso8601(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    private fun runOnMain(block: () -> Unit) {
        val looper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (looper == null) block() else Handler(looper).post(block)
    }

    private fun runOnMainSafely(label: String, block: () -> Unit) {
        runOnMain {
            runCatching(block).onFailure { log("$label failed") }
        }
    }

    private fun log(message: String) {
        // never logs the token or secret
        if (debug) android.util.Log.d("TrackHub", message)
    }
}
