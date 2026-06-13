package com.trackhub

import android.content.Context
import android.os.Build
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * TrackHub Android SDK — install reporting with Google Play Install Referrer
 * attribution and optional SDK Signature. Mirrors the iOS SDK's security model:
 *
 *  - HTTPS is enforced (token would otherwise leak in transit).
 *  - The ingest token and SDK secret live in memory only — never written to
 *    disk and never logged (debug logging prints status, never credentials).
 *  - Install reports are HMAC-signed when an SDK secret is configured.
 *  - Only a non-secret "install sent" flag and nothing else is persisted.
 *  - No revenue/event sending from the device: revenue flows server-side via
 *    Apphud webhooks, so no second secret ships in the binary.
 *
 * Usage (on app launch, after Apphud starts):
 * ```
 * TrackHub.configure(
 *     context = applicationContext,
 *     endpoint = "https://postbacks.example.com",
 *     ingestToken = "<app ingest token>",
 *     userId = Apphud.userId(),
 *     sdkSecret = "<app sdk secret>" // optional; enables SDK Signature
 * )
 * ```
 */
object TrackHub {

    private const val PREFS = "trackhub"
    private const val INSTALL_SENT_KEY = "install_sent"
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var endpoint: String? = null
    @Volatile private var ingestToken: String? = null
    @Volatile private var sdkSecret: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var debug = false

    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        endpoint: String,
        ingestToken: String,
        userId: String,
        sdkSecret: String? = null,
        debug: Boolean = false,
    ) {
        // Plaintext HTTP would expose the ingest token and let a MITM poison
        // attribution. localhost only for local development.
        val isHttps = endpoint.startsWith("https://")
        val isLocal = endpoint.startsWith("http://localhost") || endpoint.startsWith("http://127.0.0.1")
        if (!isHttps && !isLocal) {
            log("refusing non-HTTPS endpoint — SDK not configured")
            return
        }
        this.endpoint = endpoint.trimEnd('/')
        this.ingestToken = ingestToken
        this.sdkSecret = sdkSecret
        this.userId = userId
        this.debug = debug

        val appContext = context.applicationContext
        io.execute { reportInstallIfNeeded(appContext) }
    }

    // MARK: - Install reporting

    private fun reportInstallIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(INSTALL_SENT_KEY, false)) return

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
        val body = JSONObject()
            .put("user_id", uid)
            .put("platform", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("occurred_at", iso8601(Date()))
        appVersion(context)?.let { body.put("app_version", it) }
        if (!referrer.isNullOrEmpty()) body.put("install_referrer", referrer)

        val ok = post("install", body.toString())
        if (ok) {
            prefs.edit().putBoolean(INSTALL_SENT_KEY, true).apply()
            log("install reported")
        } else {
            log("install report failed — will retry on next launch")
        }
    }

    // MARK: - Networking

    private fun post(path: String, rawBody: String): Boolean {
        val base = endpoint ?: return false
        val token = ingestToken ?: return false
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
                conn.setRequestProperty("X-TrackHub-Signature", Signing.sign(secret, ts, token, rawBody))
            }

            conn.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    private fun appVersion(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private fun iso8601(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    private fun log(message: String) {
        // never logs the token or secret
        if (debug) android.util.Log.d("TrackHub", message)
    }
}
