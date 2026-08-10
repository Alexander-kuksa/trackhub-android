# TrackHub Android SDK 3.0

> Current public release: `3.0.3` · measurement geography is server-owned
> and is not cached by the SDK · minSdk 26 · Java/JVM 17

TrackHub measures installations, 30-minute sessions, engagement and bounded
Google/OpenAI click context. Apphud, RevenueCat and other billing SDKs remain
entirely owned by the host application.

## Install

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:3.0.3")
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
are not TrackHub constraints. The desired link is persisted immediately, while
delivery waits for the production install acknowledgement. Version 3.0.1 also
self-heals queues created by the 3.0.0 identity/install ordering defect.

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
from Apphud. `countryCode` is only a host-provided request fallback. Daively
resolves current geography on every official SDK delivery from its trusted edge
or local country-only GeoIP database, then falls back to the host value and the
stored installation country. The response never returns `country` or `eea` to
the SDK. Version 3.0.3 deletes the short-lived geography cache and refresh job
introduced in 3.0.2. TrackHub does not infer geography from Locale or GPS and
never discovers, persists or sends an IP address. Both Google consent values
stay `UNKNOWN` in the no-CMP SDK configuration; the public consent API is
optional. Daively applies the approved app policy server-side: missing values
default to `GRANTED` globally, including confirmed EEA and unknown geography.
Explicit CMP denial always wins. Confirmed-EEA and unknown-geo grants are
separately observable and have operator kill switches. Advertising-ID
availability does not itself grant either Google consent signal; the server
policy does. TrackHub shows no additional consent UI.

The disk-first bounded queue protects install and transaction context. Network
outages retry and do not trip the runtime circuit. Internal storage/codec/
invariant failures disable measurement only for the current process; privacy
erasure remains operational.

TrackHub must run in the application's main process. Exclude TrackHub state from
backup/device transfer with the XML rules shipped by the library.

See [INTEGRATION.md](INTEGRATION.md) for the complete contract and QA checklist.
