# Daively Android SDK

The Daively SDK provides first-party mobile measurement for installations,
30-minute foreground sessions, product engagement, attribution links and
bounded purchase context. It does not depend on Apphud, RevenueCat or another
billing SDK.

Current release: `3.0.3` · Requirements: Android API 26+, Java/JVM 17

## Installation

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:3.0.3")
}
```

Start the SDK with the key from Daively → App → Setup:

```kotlin
TrackHub.start(
    applicationContext,
    TrackHubConfig(sdkKey = "<DAIVELY_SDK_KEY>"),
)
```

The SDK key is a credential. Do not log it or include it in URLs, analytics or
crash reports. Startup, disk access and delivery are asynchronous and do not
need to gate the application's UI.

## Events and attribution

```kotlin
TrackHub.trackEvent("tutorial_done")
TrackHub.trackEvent("tutorial_done", deduplicationId = "tutorial-v1")
```

Use `deduplicationId` only when the host may retry the same logical event.
Without it, every call represents a distinct event. Billing lifecycle and
revenue should come from an authenticated billing or store-server source, not
from custom client events.

After a successful Play purchase, the optional purchase observation API can
provide short-lived matching context without sending price or currency.

## Optional billing identity

Daively is provider-neutral. The host application may associate an Apphud,
RevenueCat or custom identity without adding that provider as an SDK dependency:

```kotlin
TrackHub.setExternalIdentity("apphud", Apphud.userId())
TrackHub.setExternalIdentity("revenuecat", Purchases.sharedInstance.appUserID)
```

Repeat the call after provider login, logout or restore. Pass `null` to clear an
identity. Daively does not call another billing SDK on the application's behalf.

## Privacy and consent

Google Advertising ID and App Set ID support is optional and disabled by
default. Applications that enable it must add the required Google runtime and
permission, obtain the required consent, and keep their Google Play Data safety
answers aligned with the destinations configured in Daively.

Consent is supplied by the host application's CMP or consent UI:

```kotlin
TrackHub.updateGoogleAdsConsent(currentGoogleConsent)
TrackHub.updatePiplConsent(currentPiplConsent)
```

The SDK reports these signals to Daively and does not display its own consent
prompt or send events directly to Google. Advertising destinations and their
delivery policy are configured separately by the Daively operator. Do not
enable an advertising destination without the permissions and disclosures
required for the application's users and regions.

`countryCode` is an optional actual-country fallback, not a value inferred from
language or locale. The SDK does not use GPS and does not discover, store or
send an IP address.

```kotlin
TrackHub.updateCountryCode("DE")
TrackHub.gdprForgetMe(applicationContext)
```

`gdprForgetMe()` stops local measurement, clears queued measurement and keeps
the erasure request retryable across relaunches.

## Reliability

Reports enter a bounded durable queue before delivery. Network failures retry
with backoff and do not block the host application. Internal storage, codec or
invariant failures open a process-local fail-silent circuit; privacy erasure
remains available. TrackHub must run in the application's main process.

See [INTEGRATION.md](INTEGRATION.md) for the complete API and release checks.
