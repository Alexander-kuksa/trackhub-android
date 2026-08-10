# TrackHub Android SDK 3.0

> Current release: `3.0.0` · minSdk 26 · Java/JVM 17 · no billing-SDK dependency

TrackHub measures installations, 30-minute sessions, engagement and bounded
Google/OpenAI click context. Apphud, RevenueCat and other billing SDKs remain
entirely owned by the host application.

## Install

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:3.0.0")
}
```

Optional GAID/App Set ID artifacts are compile-only in TrackHub; add them only
when the app's consent policy permits their collection. TrackHub does not add
`AD_ID` permission automatically.

## Start and optional provider link

```kotlin
TrackHub.start(
    applicationContext,
    TrackHubConfig(sdkKey = "<TRACKHUB_SDK_KEY>"),
)

// Apphud, any host-compatible version:
TrackHub.setExternalIdentity("apphud", Apphud.userId())

// RevenueCat:
TrackHub.setExternalIdentity("revenuecat", Purchases.sharedInstance.appUserID)
```

Repeat the identity call after login/logout/restore; pass `null` to clear one
provider. Supported namespaces are `apphud`, `revenuecat`, `custom:<slug>`.
TrackHub imports none of them, so billing startup order and version resolution
are not TrackHub constraints.

## Purchases, consent and privacy

Apphud/S2S/store-server events supply money and lifecycle. Apple/Google store
verification is optional. For Google App Conversion matching, forward a
successful Play purchase with `trackPurchaseObserved`; it sends transaction ID
and short-lived device context, never value or currency.

```kotlin
TrackHub.updateGoogleAdsConsent(currentGoogleConsent)
TrackHub.updatePiplConsent(currentPiplConsent)
TrackHub.updateCountryCode("DE")
TrackHub.gdprForgetMe(applicationContext)
```

Country is optional actual measurement geography, not language, and is not read
from Apphud. A trusted edge country can override it. Unknown consent is never
treated as granted.

The disk-first bounded queue protects install and transaction context. Network
outages retry and do not trip the runtime circuit. Internal storage/codec/
invariant failures disable measurement only for the current process; privacy
erasure remains operational.

TrackHub must run in the application's main process. Exclude TrackHub state from
backup/device transfer with the XML rules shipped by the library.

See [INTEGRATION.md](INTEGRATION.md) for the complete contract and QA checklist.
