# TrackHub Android SDK 2.0 — developer integration guide

This is the canonical native Android implementation guide. Apphud is assumed to be present and
authoritative for subscription revenue. No application backend or login is required.

## 1. Architecture

```text
Android app
  Apphud SDK ───────────────► Apphud ── webhook ──► TrackHub
       ▲                                                │
       │ automatic custom attribution                   │ revenue join
       │                                                ▼
  TrackHub SDK ─ install/session/events/context ──► TrackHub
                                                    ├─ Google App Conversion API
                                                    ├─ Google Data Manager/offline import
                                                    ├─ ChatGPT Ads CAPI
                                                    └─ product analytics
```

The SDK measures the device. Apphud reports verified billing lifecycle and money. TrackHub joins
them by identity/install/transaction without making the mobile app a financial source.

## 2. Requirements

- minSdk 26
- compileSdk 34 or newer
- Java/JVM 17
- Kotlin 2.1-compatible toolchain
- TrackHub Android 2.0.0
- Apphud Android 3.4.2 or a compatible newer 3.x
- SDK Signature enabled and a TrackHub SDK Key copied from Setup
- Apphud webhook linked to the same TrackHub app

SDK 2.0 removes legacy `configure`, `setUserId`, Apphud adapters and backend callbacks. Old mobile
API source compatibility is intentionally not retained.

## 3. Gradle

```kotlin
// settings.gradle.kts or repositories block
maven("https://jitpack.io")

// app/build.gradle.kts
dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:2.0.0")
}
```

For TrackHub GAID/App Set collection add the Google artifacts listed in README and, only when your
policy requires it:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID" />
```

## 4. Application startup

Cold-start order:

1. forward the launch `Intent.data` if present;
2. restore CMP consent state;
3. start Apphud;
4. construct `TrackHubConfig` from one copied SDK Key;
5. call `TrackHub.start`;
6. do not wait for TrackHub before drawing the first UI.

```kotlin
class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Apphud.start(applicationContext, BuildConfig.APPHUD_API_KEY)

        TrackHub.start(
            applicationContext,
            TrackHubConfig(
                sdkKey = BuildConfig.TRACKHUB_SDK_KEY,
                countryCode = countryProvider.measurementCountry,
                collectAdvertisingId = consentStore.adUserData,
                firebaseAppInstanceId = firebaseAppInstanceId,
                googleAdsConsent = consentStore.trackHubGoogleConsent(),
                piplConsent = consentStore.trackHubPiplConsent(),
                attributionChangedHandler = { snapshot ->
                    mainState.onAttribution(snapshot)
                },
                deferredDeepLinkHandler = { path ->
                    path?.let(router::open)
                },
            ),
        )
    }
}
```

TrackHub starts asynchronously. SharedPreferences migration, disk queue work, Install Referrer and
network access do not block `Application.onCreate`.

## 5. Identity behavior

The host does not pass identity. TrackHub reads `Apphud.userId()` and uses an app-scoped fallback
only until Apphud is ready. On foreground/event boundaries it checks again. If restore/login changes
the Apphud id, the SDK sends a signed `sdk/identity` update tied to the same `install_uid`; the server
closes the old binding and preserves its history instead of creating a second install.

Apphud attribution delivery is built in through the official
`Apphud.setAttribution(ApphudAttributionData(...), CUSTOM)` API. TrackHub never starts Apphud.

## 6. Consent

Map all three states explicitly:

```kotlin
fun ConsentStore.trackHubGoogleConsent() = TrackHubGoogleAdsConsent(
    adUserData = when (adUserDataState) {
        YES -> TrackHubConsentStatus.GRANTED
        NO -> TrackHubConsentStatus.DENIED
        UNRESOLVED -> TrackHubConsentStatus.UNKNOWN
    },
    adPersonalization = /* same mapping */,
    isEea = geo.isEea,
)
```

After settings/CMP change call `updateGoogleAdsConsent` and/or `updatePiplConsent`. `UNKNOWN` omits
the field. It must not be silently treated as granted.

## 7. Activity/deep-link wiring

For the first Activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    intent?.data?.let { TrackHub.handleDeepLink(applicationContext, it) }
    super.onCreate(savedInstanceState)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { TrackHub.handleDeepLink(it) }
}
```

The SDK reads Google Play Install Referrer asynchronously. A 3-second watchdog falls back to organic
if a vendor service hangs. The install payload enters the durable queue before the first session, and
deferred resolution waits for the install, eliminating click-consumption races.

## 8. Event instrumentation

```kotlin
TrackHub.trackSalesEvent(TrackHubSalesEvent.ONBOARDING_SHOWN)
TrackHub.trackSalesEvent(TrackHubSalesEvent.PAYWALL_SHOWN, TrackHubSalesPlacement.ONBOARDING)
TrackHub.trackSalesEvent(TrackHubSalesEvent.PURCHASE_CTA_TAPPED, TrackHubSalesPlacement.ONBOARDING)
TrackHub.trackEvent("tutorial_completed", mapOf("variant" to "short"))
```

Instrumentation rules:

- fire visibility events when the screen is actually visible;
- fire CTA before billing begins;
- use stable names and bounded JSON-safe parameters;
- do not include email, phone, user text or other PII;
- do not emit client revenue or mirror Apphud lifecycle events.

## 9. Purchase context

```kotlin
fun onVerifiedPurchase(purchase: Purchase) {
    TrackHub.trackPurchaseObserved(
        transactionId = purchase.orderId ?: return,
        productId = purchase.products.firstOrNull(),
    )
}
```

Use only for enabled Google App Conversion purchase mappings. It is not needed for TrackHub revenue
analytics itself. Apphud webhook value/currency wins; unmatched context expires after 72 hours.

## 10. Privacy

Wire the user-facing device measurement deletion action directly:

```kotlin
TrackHub.gdprForgetMe()
```

The call synchronously sets an atomic stop and persists a small job before asynchronous cleanup.
Events buffered before the action are removed. Server/network errors do not re-enable tracking. The
job retries on launch/foreground using the device install token or a signed privacy-only recovery.

The optional completion reports server confirmation when the process stays alive. Product UI should
not wait for it to claim that local tracking stopped.

For authenticated account-wide deletion, call the TrackHub server/admin API from trusted backend
infrastructure. Never embed an S2S key in Android.

## 11. FCM

```kotlin
class MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        TrackHub.setPushToken(applicationContext, token)
    }
}
```

The SDK only forwards an existing token and never requests notification permission.

## 12. Test Lab

1. start an isolated test in TrackHub;
2. copy the generated Test Lab config;
3. clear app data or reinstall;
4. launch, enter/leave foreground, show a paywall, fire a custom event;
5. complete an Apphud sandbox purchase if the mapping is under test;
6. verify signature, install, session, identity, event and join timeline;
7. confirm isolated mode did not call Google;
8. run live Google canary only after explicit approval.

## 13. Crash/outage test matrix

- server closed/DNS failure: application stays responsive, queue grows within bounds;
- 500/429: full-jitter retry, no tight loop;
- process kill after enqueue: report survives under `noBackupFilesDir`;
- process kill after ACK: possible duplicate is server-deduplicated;
- corrupt queue: file is quarantined and host does not crash;
- hung Install Referrer: organic fallback after watchdog;
- wrong device clock: signed event stays queued and uses trusted clock correction;
- `gdprForgetMe` offline + process kill + online restart: no tracking resumes and erase completes;
- SDK secret rotation: previous key accepted during grace, new key works immediately.

## 14. Release checklist

- [ ] API 26+, Java 17, Kotlin 2.1-compatible build
- [ ] one compatible Apphud dependency resolves
- [ ] Apphud starts before TrackHub
- [ ] SDK Key never appears in logs/URLs/analytics
- [ ] consent mapping distinguishes unknown/denied/granted
- [ ] actual country is supplied when known
- [ ] launch/runtime intents are forwarded
- [ ] event names/placements match the catalog
- [ ] no client-authored revenue is sent
- [ ] privacy action tested across offline process restart
- [ ] unit and connected instrumentation tests pass
- [ ] TrackHub Test Lab passes on the release candidate

## 15. Troubleshooting

| Symptom | Check |
|---|---|
| SDK not detected | SDK Key/app, clean install, HTTPS, signature enabled |
| session before install | use 2.0.0; install/referrer watchdog queues install first |
| Apphud revenue not joined | webhook app/secret, transaction id, identity binding |
| deferred path missing | Play referrer contains TrackHub match token; install was acknowledged |
| no GAID | consent, AD_ID permission, ads-identifier runtime, LAT |
| conversion blocked | consent/PIPL/country, mapping, click TTL, destination readiness |
| privacy completion delayed | expected during outage; local tracking is already stopped |
| repeated 401 | clock, SDK Key rotation and server grace period |
