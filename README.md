# TrackHub Android SDK

Kotlin SDK for TrackHub: install/referrer attribution, automatic app sessions, custom engagement
events and the App Conversion purchase bridge. Revenue stays authoritative in Apphud/S2S; the SDK
never accepts a price or currency.

> **Build status.** GitHub Actions (`.github/workflows/android-ci.yml`) compiles the library
> (`:trackhub:assembleRelease`) and runs the unit tests (`:trackhub:test`) on every push and PR,
> so a green check confirms it **compiles** and that the HMAC signature matches the shared parity
> vector (`SigningTest`) byte-for-byte with the server and the iOS SDK. What CI does **not** cover
> is the Play Store service's real production response. CI now compiles and runs an Android
> instrumentation test on API 34 for signed payloads, Test Lab token isolation, retry/queue drain,
> and the absence of the Advertising ID permission. Still smoke-test Play Install Referrer on a Play-enabled device
> before publishing because the emulator cannot reproduce every Play Store state.

## Building & testing

```bash
# CI provisions Gradle 8.7 (this repo ships without a committed wrapper binary).
# Locally, install JDK 17 + Gradle 8.7 (or open in Android Studio), then:
gradle :trackhub:test            # unit tests, incl. the signature parity vector
gradle :trackhub:assembleRelease # build the release AAR
```

## Why Android differs from iOS

There is no SKAdNetwork and no AdServices token on Android. The acquisition signal is the
**Google Play Install Referrer**, handed to the app once on first launch. The SDK forwards it to
TrackHub, which derives the channel (Google Ads / organic) and campaign. It also sends non-financial
engagement events and a transaction-only purchase observation so TrackHub can join the real device
context to the authoritative Apphud webhook before calling Google.

## Install

Repository: `https://github.com/Alexander-kuksa/trackhub-android`. The simplest way to consume a
public GitHub Android library is **JitPack** (builds from a release tag — no manual artifact
publishing):

```kotlin
// settings.gradle.kts (consumer app)
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
implementation("com.github.Alexander-kuksa:trackhub-android:1.6.0")
```

(Requires a `1.6.0` git tag on the repo. Alternatively publish to GitHub Packages with
`./gradlew :trackhub:publish` and consume `com.trackhub:trackhub-android:1.6.0`.)

The Play Install Referrer Library is pulled in transitively.

Advertising ID support is deliberately opt-in so apps that do not use it do
not inherit the `AD_ID` permission. If `collectAdvertisingId = true`, add the
Google Play services identifier runtime to the host app as well:

```kotlin
implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
implementation("com.google.android.gms:play-services-appset:16.1.0")
```

Keep `collectAdvertisingId = false` unless `ad_user_data` consent is available.
The SDK also checks that persisted consent before reading GAID. If GAID is
unavailable or limited, the official App Set ID is used and `lat` retains the
actual limit-ad-tracking/personalization state; it is
never collected unless identifier collection is enabled and `ad_user_data` is
granted. Version 18.2.0 preserves TrackHub's Android 5.0 / API 21 floor; Google
18.3.0 requires API 23.

## Usage

```kotlin
import com.trackhub.TrackHub

TrackHub.setGoogleAdsConsent(
    context = applicationContext,
    adUserData = consent.adUserData,
    adPersonalization = consent.adPersonalization,
    eea = consent.isEea
)

// Mainland China only, after your PIPL consent UI resolves these values:
// TrackHub.setPiplConsent(
//     context = applicationContext,
//     piplConsent = consent.pipl,
//     crossBorderTransferConsent = consent.crossBorder,
//     adsMeasurementConsent = consent.adsMeasurement
// )

// On app launch (Application.onCreate), after Apphud starts.
// Copy the app values from TrackHub → SDK integration. AppBackend below is
// your authenticated API; it keeps the TrackHub S2S secret off the device.
TrackHub.configure(
    context = applicationContext,
    endpoint = "https://postbacks.daively.com", // your ingest domain
    ingestToken = "<app ingest token>",
    userId = Apphud.userId(),
    sdkSecret = "<app sdk secret>", // required for the purchase bridge and ChatGPT Ads
    countryCode = measurementCountry, // actual ISO-3166 country, not UI language
    collectAdvertisingId = true,   // only with ad_user_data consent
    apphudCollectDeviceIdentifiersHandler = {
        Apphud.collectDeviceIdentifiers()
    },
    backendAttributionProvider = { userId, completion ->
        AppBackend.fetchTrackHubAttribution(userId, completion)
    },
    backendPrivacyErasureHandler = { userId, reason, completion ->
        AppBackend.eraseTrackHubUser(userId, reason, completion)
    },
    apphudAttributionHandler = { data, completion ->
        // Adapt `data` to Apphud's custom-attribution API in the host app.
        sendTrackHubAttributionToApphud(data, completion)
    },
    attributionChangedHandler = { attribution ->
        updateAttributionUi(attribution)
    },
    deferredDeepLinkHandler = { path ->
        path?.let(::routeDeferredPath)
    }
)

// In your FirebaseMessagingService. TrackHub does not initialize Firebase or
// request Android 13 notification permission; it only forwards this host-owned
// token for a data-only uninstall probe.
override fun onNewToken(token: String) {
    TrackHub.setPushToken(applicationContext, token)
}

// Non-financial engagement events:
TrackHub.trackEvent("trial_started")
TrackHub.trackEvent("paywall_viewed", callbackParams = mapOf("placement" to "onboarding"))

// In the successful Play/Apphud purchase callback. No amount is sent here:
purchase.orderId?.let { orderId ->
    TrackHub.trackPurchaseObserved(
        transactionId = orderId,
        productId = purchase.products.firstOrNull()
    )
}

// Forward Google (gclid/gbraid) or ChatGPT Ads (oppref) deep-link ids:
TrackHub.handleDeepLink(applicationContext, intent.data!!)
```

`configure` reports the install once, starts automatic foreground session tracking and flushes the
bounded offline queue. `sdkSecret` is optional for ordinary measurement but required for
`trackPurchaseObserved` and ChatGPT Ads conversion reporting, because an unsigned bearer-token
request must never control an ads conversion. For a cold launch from a ChatGPT ad, call the
context overload of `handleDeepLink` before `configure`: the bounded `oppref` is included in the
signed install and exactly one ad-click session report. `userId` is optional; the SDK creates a
persistent app-scoped fallback. Firebase is not required. `getAttribution`,
`resolveDeferredDeepLink` and `forgetDevice` are also public for explicit refresh, routing and
privacy-erasure flows. Attribution and erasure fail closed unless the corresponding `AppBackend`
callback is supplied. That backend authenticates the signed-in user, calls TrackHub with a linked
S2S connection's `X-TrackHub-Token`, and returns only the raw attribution response or success
flag; the S2S token must never reach the app.

TrackHub-owned Google Play links add an opaque `trackhub_match_token` to Install Referrer. The SDK
returns that token once to `/resolve`, consumes it after a successful response and never uses IP or
User-Agent matching to retrieve a private deferred path.

Uninstall measurement additionally requires an FCM connection linked to the app in TrackHub.
The SDK persists the latest FCM token and re-sends it after every `configure`; the server counts an
uninstall only when FCM explicitly returns `UNREGISTERED`, never on a timeout or generic error.

When an SDK event is explicitly mapped to a Google App Conversion custom event, bounded primitive
`callbackParams` become Google `app_event_data`; `partnerParams` are retained in TrackHub only.

## Security properties (parity with the iOS SDK, reviewed)

- **HTTPS enforced** — non-HTTPS endpoints are refused (localhost exempt for development).
- **No secrets at rest / in logs** — the ingest token and SDK secret are held in memory only.
  The bounded offline queue stores report bodies but never credentials; debug logging prints
  status, never tokens or secrets.
- **SDK Signature v2** —
  `HMAC-SHA256(secret, "<timestamp>.<ingestToken>.<endpointScope>.<rawBody>")`, lowercase
  hex, with signature-version `2`. The server enforces a ±5-minute anti-replay window,
  endpoint binding and constant-time comparison; secret rotation has a 7-day grace period.
- **Backend-only sensitive operations** — the app-wide SDK secret cannot read another user's
  attribution or erase an arbitrary identity. The host backend authorizes those operations with
  its S2S secret and returns only the scoped result to the SDK.
- **Consent-gated identity** — user id, OS/app version, Play referrer, engagement events and a
  stable transaction/product id. GAID/App Set ID is collected only when the host opts in and persisted
  `ad_user_data` consent is true; limited/zero advertising identifiers are discarded. No device
  fingerprinting. The server encrypts purchase context and deletes it after Apphud join or 72h.
- **Spec-complete event context** — a stable first-open timestamp accompanies every event. Supply
  the actual ISO country through `countryCode` (or configure TrackHub's trusted edge geo header);
  the SDK deliberately does not mislabel Locale/language as geography.
- **PIPL fail-closed support** — `setPiplConsent` persists mainland-China processing,
  cross-border-transfer and ads-measurement choices for server-side enforcement.
- **Platform dependencies only** — the official Play Install Referrer, Google Advertising ID and App Set ID
  clients; Apphud remains a host adapter rather than a hard SDK dependency.

## Hardening note

Like any client SDK (and like the paid Adjust SDK), the `sdkSecret` ships inside the app. SDK
Signature raises the cost of forging organic installs, it does not make it impossible. There is
no certificate pinning by default (ATS-equivalent TLS applies); add pinning via
`network_security_config.xml` if your threat model requires it.
