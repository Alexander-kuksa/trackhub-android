# TrackHub Android SDK 2.0

> Current release: `2.0.3` · minSdk 26 · Java/JVM 17 · Kotlin 2.1 · Apphud 3.4.2

TrackHub measures installs, automatic sessions and engagement events, captures Google/OpenAI click
references, and automatically joins the installation to Apphud. It does not require an application
backend, login, identity callback or manually assembled TrackHub credentials.

Apphud webhooks are authoritative for purchases, trials, renewals, refunds, value and currency.
TrackHub joins those facts to the SDK installation and sends eligible conversions to configured
Google App Conversion API, Data Manager/offline import and other destinations.

The full implementation/QA guide is [INTEGRATION.md](INTEGRATION.md).

## Install

JitPack example:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:2.0.3")
}
```

TrackHub 2.0 has a first-class dependency on Apphud 3.4.2. The host should use a compatible Apphud
3.x version and a Kotlin toolchain able to consume Kotlin 2.1 metadata.

If TrackHub itself should read GAID/App Set ID, add:

```kotlin
implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
implementation("com.google.android.gms:play-services-appset:16.1.0")
```

TrackHub does not add `AD_ID` permission automatically. Declare it only when your consent/policy
allows advertising identifier collection. `INTERNET` is included by the library manifest.

TrackHub must be started and called from the application's main process. The SDK intentionally does
not coordinate its singleton or queue across multiple Android processes.

Exclude TrackHub identifiers from Android backup and device transfer. The library ships copy-ready
rules as `@xml/trackhub_backup_rules` (Android 11 and older) and
`@xml/trackhub_data_extraction_rules` (Android 12+). Merge their `trackhub.xml` exclusions into the
application's existing rules, or reference them directly when the app has no other backup policy:

```xml
<application
    android:fullBackupContent="@xml/trackhub_backup_rules"
    android:dataExtractionRules="@xml/trackhub_data_extraction_rules" />
```

## Minimal integration

Copy **TrackHub SDK Key** from **TrackHub → App → Setup**. Start Apphud first:

```kotlin
import com.apphud.sdk.Apphud
import com.trackhub.TrackHub
import com.trackhub.TrackHubConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Apphud.start(applicationContext, "<APPHUD_API_KEY>")
        TrackHub.start(
            applicationContext,
            TrackHubConfig(sdkKey = "<TRACKHUB_SDK_KEY>"),
        )
    }
}
```

That is sufficient for install and automatic foreground-session measurement. TrackHub reads
`Apphud.userId()`, observes later identity changes, maps them to the same `install_uid`, and sends
TrackHub attribution back to Apphud as custom attribution. Do not pass a user id or implement a
bridge callback.

## Production configuration

```kotlin
val trackHubConfig = TrackHubConfig(
    sdkKey = BuildConfig.TRACKHUB_SDK_KEY,
    environment = TrackHubEnvironment.Production,
    debugLogging = false,
    countryCode = measurementCountry,
    collectAdvertisingId = consent.adUserData,
    firebaseAppInstanceId = firebaseAppInstanceId, // only if Firebase already exists
    googleAdsConsent = TrackHubGoogleAdsConsent(
        adUserData = if (consent.adUserData) TrackHubConsentStatus.GRANTED else TrackHubConsentStatus.DENIED,
        adPersonalization = if (consent.personalizedAds) TrackHubConsentStatus.GRANTED else TrackHubConsentStatus.DENIED,
        isEea = consent.isEea,
    ),
    piplConsent = TrackHubPiplConsent(
        personalInformation = TrackHubConsentStatus.UNKNOWN,
        crossBorderTransfer = TrackHubConsentStatus.UNKNOWN,
        adsMeasurement = TrackHubConsentStatus.UNKNOWN,
    ),
    attributionChangedHandler = { attribution -> /* optional UI/state reaction */ },
    deferredDeepLinkHandler = { path -> path?.let(router::open) },
)
TrackHub.start(applicationContext, trackHubConfig)
```

For Test Lab use `TrackHubEnvironment.TestLab(shortLivedToken)`. Test Lab has a separate disk queue
and invalid/expired test tokens fail closed.

The SDK Key is a versioned configuration credential containing this app's TrackHub endpoint and
ingest credentials. Never log it or place it in URLs, analytics properties, crash metadata or
screenshots. Mobile credentials are extractable; server limits, HMAC, per-install credentials and
deduplication remain mandatory defenses.

## Events

```kotlin
TrackHub.trackSalesEvent(TrackHubSalesEvent.ONBOARDING_SHOWN)
TrackHub.trackSalesEvent(
    TrackHubSalesEvent.PAYWALL_SHOWN,
    TrackHubSalesPlacement.ONBOARDING,
)
TrackHub.trackSalesEvent(
    TrackHubSalesEvent.PURCHASE_CTA_TAPPED,
    TrackHubSalesPlacement.ONBOARDING,
)

TrackHub.trackEvent(
    "tutorial_done",
    callbackParams = mapOf("step" to 3),
    partnerParams = mapOf("experiment" to "paywall_b"),
)
```

Fire “shown” at the visible UI boundary and CTA before Play Billing begins. Do not send PII, price,
currency or revenue. Apphud lifecycle events are not mirrored from the client.

For enabled Google App Conversion purchase mappings:

```kotlin
TrackHub.trackPurchaseObserved(
    transactionId = purchase.orderId,
    productId = purchase.products.firstOrNull(),
)
```

This call sends transaction identity plus short-lived device context, never money. Apphud remains
the economic source of truth.

## Consent updates

```kotlin
TrackHub.updateGoogleAdsConsent(
    TrackHubGoogleAdsConsent(
        adUserData = TrackHubConsentStatus.GRANTED,
        adPersonalization = TrackHubConsentStatus.DENIED,
        isEea = true,
    ),
)
TrackHub.updatePiplConsent(updatedPiplConsent)
TrackHub.updateCountryCode("DE")
TrackHub.updateFirebaseAppInstanceId(firebaseId)
```

`UNKNOWN` omits a signal and is not equivalent to denial. For EEA traffic, unresolved required
consent can correctly block Google forwarding. Country must be actual measurement geography, not
device language.

## Deep links and deferred paths

Forward launch intents before `TrackHub.start` and runtime intents afterward:

```kotlin
intent.data?.let { TrackHub.handleDeepLink(applicationContext, it) }
```

TrackHub parses bounded `gclid`, `gbraid` and `oppref` references. Android deferred routing uses the
opaque TrackHub match token carried by Google Play Install Referrer. `/resolve` is not called before
the install report is durably queued/acknowledged, so the click cannot be consumed ahead of install.

```kotlin
TrackHub.resolveDeferredDeepLink { path -> path?.let(router::open) }
```

## Attribution and privacy

```kotlin
TrackHub.attribution { snapshot -> /* optional read */ }
TrackHub.gdprForgetMe(applicationContext)
```

The SDK forwards attribution revisions to Apphud automatically. Use the `Context`
overload shown above so the request is durable even before `TrackHub.start()`.
The privacy state belongs to the app installation, not the rotatable SDK Key.
`gdprForgetMe()` has device scope:

1. an atomic in-process stop happens before returning;
2. the measurement queue and local identifiers are cleared;
3. a small crash-safe job is persisted under `noBackupFilesDir`;
4. retries run after transport failures, relaunches and foregrounds;
5. a stale/missing install token uses a signed privacy-only recovery endpoint;
6. tracking never re-enables because TrackHub is down;
7. install token and install uid are deleted only after server `2xx`/`410`.

Account-wide erasure for an authenticated multi-device user is a separate trusted backend/admin
operation and is deliberately absent from the mobile API.

## Push token / uninstall measurement

Forward the FCM token already owned by your Firebase Messaging integration:

```kotlin
override fun onNewToken(token: String) {
    TrackHub.setPushToken(applicationContext, token)
}
```

TrackHub does not initialize Firebase, request notification permission or show notifications.

## Failure guarantees

| Area | Guarantee |
|---|---|
| Public calls | state/network work is asynchronous except the small crash-safe privacy write in `gdprForgetMe`; host exceptions are not propagated |
| Queue | `AtomicFile` under `noBackupFilesDir`, disk-first before delivery |
| Bounds | 1,000 reports, 4 MiB total, 64 KiB per report |
| Overflow | ordinary oldest reports evicted before the production install |
| Delivery | one network worker; state executor remains available while server hangs |
| Network | bounded connect/read timeouts and 64 KiB response cap |
| Retries | transport/408/429/5xx, full-jitter exponential backoff, cap 5 min |
| Install Referrer | 3-second watchdog falls back to organic; a late vendor result is sent as an attribution refinement |
| Clock skew | trusted server time corrects signing only; event stays queued |
| Corruption | queue is quarantined, not allowed to crash the app |
| Privacy | immediate durable stop; retry survives process death/server outage |

Install is queued before the first session. This prevents a slow/hung Install Referrer service from
letting the session consume attribution context first. Duplicate delivery after a crash is safe due
to server idempotency.

## Build and test

```bash
gradle :trackhub:testDebugUnitTest
gradle :trackhub:connectedDebugAndroidTest
```

Use JDK 17 and an API 26+ emulator/physical device. The instrumentation suite includes a TrackHub
outage, disk queue persistence, signed retries and privacy erasure across a later healthy start.
