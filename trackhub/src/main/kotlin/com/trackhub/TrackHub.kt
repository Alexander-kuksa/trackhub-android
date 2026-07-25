package com.trackhub

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.Tasks
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
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
object TrackHub {

    /** SDK version reported to the platform for integration detection. */
    const val SDK_VERSION = "1.6.0"

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
    private val io = Executors.newSingleThreadExecutor()

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
    private var startedActivities = 0

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
        if (hasPersistedPrivacyDisable(configuredAppContext)) {
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
        apphudCollectDeviceIdentifiersHandler?.let { handler -> runOnMain { handler() } }
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
            runOnMain { completion(current) }
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
                completion?.let { callback -> runOnMain { callback(false) } }
                return@execute
            }
            runOnMain {
                handler(uid, reason.take(256)) { accepted ->
                    io.execute {
                        if (accepted) {
                            trackingDisabled = true
                            currentAttribution = null
                            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
                                .apply()
                            pendingGclid = null
                            pendingGbraid = null
                            pendingOpenAiOppref = null
                            userId = null
                            firebaseAppInstanceId = null
                        }
                        completion?.let { callback -> runOnMain { callback(accepted) } }
                    }
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
        val body = deviceContextBody(context)
            .put("client_event_id", UUID.randomUUID().toString())
            .put("event_name", name)
            .put("user_id", uid)
            .put("occurred_at", iso8601(Date()))
        if (callbackParams.isNotEmpty()) body.put("callback_params", JSONObject(callbackParams))
        if (partnerParams.isNotEmpty()) body.put("partner_params", JSONObject(partnerParams))
        io.execute { sendOrQueue("sdk/track", body.toString()) }
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
        val gclid = uri.getQueryParameter("gclid")?.takeIf { it.isNotEmpty() }
        val gbraid = uri.getQueryParameter("gbraid")?.takeIf { it.isNotEmpty() }
        val oppref = normalizedOpenAiOppref(uri.getQueryParameter("oppref"))
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

    private fun hasPersistedPrivacyDisable(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.any { (key, value) ->
            key.startsWith(PRIVACY_DISABLED_PREFIX) && value == true
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
        sendOrQueue("sdk/session", body.toString())
        // sendOrQueue either delivered the exact payload or persisted it before
        // returning, so clearing the one-shot source cannot lose the click id.
        prefs.edit()
            .remove(PENDING_GCLID_KEY)
            .remove(PENDING_GBRAID_KEY)
            .remove(PENDING_OPENAI_OPPREF_KEY)
            .apply()
        pendingGclid = null
        pendingGbraid = null
        pendingOpenAiOppref = null
        fetchAttributionIfNeeded()
    }

    // MARK: - Install reporting

    private fun reportInstallIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (integrationTestToken == null && prefs.getBoolean(INSTALL_SENT_KEY, false)) return

        val client = InstallReferrerClient.newBuilder(context).build()
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

        val code = post("install", withIntegrationTestToken(body.toString()))
        if (code in 200..299) {
            if (integrationTestToken == null) prefs.edit().putBoolean(INSTALL_SENT_KEY, true).apply()
            log(if (integrationTestToken == null) "install reported" else "integration-test install reported")
            if (integrationTestToken == null) {
                sendConsentUpdate(context)
                fetchAttributionIfNeeded()
            }
        } else {
            log("install report failed — will retry on next launch")
        }
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
            completion?.let { callback -> runOnMain { callback(null) } }
            return
        }
        val uid = userId
        val provider = backendAttributionProvider
        if (uid.isNullOrBlank() || provider == null) {
            completion?.let { callback -> runOnMain { callback(null) } }
            if (provider == null) log("attribution fetch requires backendAttributionProvider")
            return
        }
        attributionFetchInFlight = true
        runOnMain {
            provider(uid) { raw ->
                io.execute {
                    attributionFetchInFlight = false
                    val snapshot = raw?.let(::parseAttribution)
                    if (snapshot != null) {
                        val changed = currentAttribution?.revision != snapshot.revision
                        currentAttribution = snapshot
                        if (changed) attributionChangedHandler?.let { handler -> runOnMain { handler(snapshot) } }
                        deliverAttributionToApphud(uid, snapshot)
                    }
                    completion?.let { callback -> runOnMain { callback(snapshot) } }
                }
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
            handler(snapshot.data) { accepted ->
                if (accepted) prefs.edit().putString(key, snapshot.revision).apply()
            }
        }
    }

    private fun resolveDeferredDeepLinkIfNeeded(
        completion: TrackHubDeferredDeepLinkHandler? = null,
    ) {
        if (trackingDisabled || deferredResolveInFlight || integrationTestToken != null) {
            completion?.let { callback -> runOnMain { callback(null) } }
            return
        }
        val handler = completion ?: deferredDeepLinkHandler ?: return
        val context = appContext ?: return
        val token = ingestToken ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DEFERRED_RESOLVE_PREFIX + hashKey(token)
        if (prefs.getBoolean(key, false)) {
            if (completion != null) runOnMain { handler(null) }
            return
        }
        val matchToken = prefs.getString(DEFERRED_MATCH_TOKEN_KEY, null)
        if (matchToken.isNullOrBlank()) {
            if (completion != null) runOnMain { handler(null) }
            return
        }
        deferredResolveInFlight = true
        val encodedToken = URLEncoder.encode(matchToken, Charsets.UTF_8.name())
        val (code, raw) = getForResponse("resolve?match_token=$encodedToken")
        deferredResolveInFlight = false
        if (code !in 200..299 || raw == null) {
            runOnMain { handler(null) }
            return
        }
        val path = runCatching { JSONObject(raw).optString("deep_link_path").takeIf { it.isNotEmpty() } }.getOrNull()
        prefs.edit().putBoolean(key, true).remove(DEFERRED_MATCH_TOKEN_KEY).apply()
        runOnMain { handler(path) }
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

    private fun sendOrQueue(path: String, rawBody: String) {
        if (trackingDisabled) return
        val context = appContext ?: return
        val preparedBody = withIntegrationTestToken(rawBody)
        val code = post(path, preparedBody)
        if (!isRetryable(code)) return // delivered or permanently rejected
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queueKey = pendingReportsKey()
        val items = runCatching { JSONArray(prefs.getString(queueKey, "[]")) }
            .getOrElse { JSONArray() }
        items.put(JSONObject().put("path", path).put("body", preparedBody))
        while (items.length() > MAX_PENDING_REPORTS) items.remove(0)
        prefs.edit().putString(queueKey, items.toString()).apply()
    }

    private fun flushPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queueKey = pendingReportsKey()
        val items = runCatching { JSONArray(prefs.getString(queueKey, "[]")) }
            .getOrElse { JSONArray() }
        val keep = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val path = item.optString("path")
            val rawBody = item.optString("body")
            val code = post(path, rawBody)
            if (isRetryable(code)) keep.put(item)
        }
        prefs.edit().putString(queueKey, keep.toString()).apply()
    }

    internal fun offlineQueueNamespace(testToken: String?): String {
        if (testToken.isNullOrEmpty()) return "production"
        val digest = MessageDigest.getInstance("SHA-256").digest(testToken.toByteArray(Charsets.UTF_8))
        return "test-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun pendingReportsKey(): String {
        val namespace = offlineQueueNamespace(integrationTestToken)
        // Keep the historical production key so an SDK update does not strand
        // real reports already buffered by an older version.
        return if (namespace == "production") PENDING_REPORTS_KEY else "${PENDING_REPORTS_KEY}_$namespace"
    }

    private fun withIntegrationTestToken(rawBody: String): String {
        val token = integrationTestToken ?: return rawBody
        return runCatching { JSONObject(rawBody).put("test_run_token", token).toString() }
            .getOrDefault(rawBody)
    }

    // HTTP status, or -1 for a retryable transport failure.
    private fun post(path: String, rawBody: String): Int {
        return postForResponse(path, rawBody).first
    }

    private fun postForResponse(path: String, rawBody: String): Pair<Int, String?> {
        val base = endpoint ?: return -1 to null
        val token = ingestToken ?: return -1 to null
        return runCatching {
            val conn = URL("$base/ingest/$token/$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            // SDK Signature over the exact bytes we send
            val secret = sdkSecret
            if (!secret.isNullOrEmpty()) {
                val ts = System.currentTimeMillis().toString()
                conn.setRequestProperty("X-TrackHub-Timestamp", ts)
                conn.setRequestProperty("X-TrackHub-Signature-Version", "2")
                conn.setRequestProperty(
                    "X-TrackHub-Signature",
                    Signing.sign(secret, ts, token, path, rawBody),
                )
            }

            conn.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val response = runCatching {
                (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
            code to response
        }.getOrDefault(-1 to null)
    }

    private fun getForResponse(path: String): Pair<Int, String?> {
        val base = endpoint ?: return -1 to null
        val token = ingestToken ?: return -1 to null
        return runCatching {
            val conn = URL("$base/ingest/$token/$path").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "TrackHub-Android/$SDK_VERSION")
            val code = conn.responseCode
            val response = runCatching {
                (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
            code to response
        }.getOrDefault(-1 to null)
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

    private fun log(message: String) {
        // never logs the token or secret
        if (debug) android.util.Log.d("TrackHub", message)
    }
}
