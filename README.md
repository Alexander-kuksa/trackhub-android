# TrackHub Android SDK

Kotlin SDK for TrackHub: reports the install with **Google Play Install Referrer** attribution
and an optional **SDK Signature** (HMAC) — the Android counterpart of the iOS SDK. Same security
model: HTTPS enforced, token/secret in memory only and never logged, install reports HMAC-signed.

> **Build status — honest note.** This module is written to production quality and mirrors the
> reviewed iOS SDK, but it was **not compiled or instrumented on this machine** (no Android /
> Gradle toolchain was available here). Open it in Android Studio (or run `gradle wrapper &&
> ./gradlew :trackhub:test :trackhub:assembleRelease`) and run it on a device/emulator before
> publishing. The one security-critical piece — the HMAC signature — is pinned to a shared
> parity vector (`SigningTest`) that already matches the server and the iOS SDK byte-for-byte,
> so a green `:trackhub:test` confirms signing interop.

## Why Android differs from iOS

There is no SKAdNetwork and no AdServices token on Android. The acquisition signal is the
**Google Play Install Referrer**, handed to the app once on first launch. The SDK forwards it to
TrackHub, which derives the channel (Google Ads / organic) and campaign. Revenue/trials still
flow server-side via Apphud webhooks — the SDK never sends money, so no second secret ships in
the binary.

## Install

```kotlin
// settings.gradle.kts (consumer app) — once published to your Maven/GitHub Packages:
dependencyResolutionManagement { repositories { /* your maven repo */ } }

// app/build.gradle.kts
implementation("com.trackhub:trackhub-android:1.0.0")
```

The Play Install Referrer Library is pulled in transitively.

## Usage

```kotlin
import com.trackhub.TrackHub

// On app launch (Application.onCreate), after Apphud starts.
// Copy the exact values from the app's page in TrackHub → SDK integration.
TrackHub.configure(
    context = applicationContext,
    endpoint = "https://postbacks.daively.com", // your ingest domain
    ingestToken = "<app ingest token>",
    userId = Apphud.userId(),
    sdkSecret = "<app sdk secret>" // optional; enables SDK Signature
)
```

That single call reports the install (once) with the referrer and, if `sdkSecret` is set,
HMAC-signs it. There is no `track()` on Android — conversion values / SKAN are Apple-only, and
revenue arrives via Apphud webhooks.

## Security properties (parity with the iOS SDK, reviewed)

- **HTTPS enforced** — non-HTTPS endpoints are refused (localhost exempt for development).
- **No secrets at rest / in logs** — the ingest token and SDK secret are held in memory only;
  only a non-secret `install_sent` flag is persisted. Debug logging prints status, never
  credentials.
- **SDK Signature** — `HMAC-SHA256(secret, "<timestamp>.<ingestToken>.<rawBody>")`, lowercase
  hex, in `X-TrackHub-Timestamp` / `X-TrackHub-Signature`. The server enforces a ±5-minute
  anti-replay window and constant-time comparison; installs additionally dedup by `(app, user)`.
- **Minimal data** — user id, OS/app version, and the Play referrer. No IDFA/GAID collection,
  no device fingerprinting.
- **Single dependency** — only the official Play Install Referrer Library.

## Hardening note

Like any client SDK (and like the paid Adjust SDK), the `sdkSecret` ships inside the app. SDK
Signature raises the cost of forging organic installs, it does not make it impossible. There is
no certificate pinning by default (ATS-equivalent TLS applies); add pinning via
`network_security_config.xml` if your threat model requires it.
