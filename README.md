# TrackHub Android SDK

Kotlin SDK for TrackHub: reports the install with **Google Play Install Referrer** attribution
and an optional **SDK Signature** (HMAC) — the Android counterpart of the iOS SDK. Same security
model: HTTPS enforced, token/secret in memory only and never logged, install reports HMAC-signed.

> **Build status.** GitHub Actions (`.github/workflows/android-ci.yml`) compiles the library
> (`:trackhub:assembleRelease`) and runs the unit tests (`:trackhub:test`) on every push and PR,
> so a green check confirms it **compiles** and that the HMAC signature matches the shared parity
> vector (`SigningTest`) byte-for-byte with the server and the iOS SDK. What CI does **not** cover
> is on-device/emulator behaviour (the Play Install Referrer handshake) — there are no
> instrumented tests yet, so smoke-test on a real device/emulator before publishing.

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
TrackHub, which derives the channel (Google Ads / organic) and campaign. Revenue/trials still
flow server-side via Apphud webhooks — the SDK never sends money, so no second secret ships in
the binary.

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
implementation("com.github.Alexander-kuksa:trackhub-android:1.0.0")
```

(Requires a `1.0.0` git tag on the repo. Alternatively publish to GitHub Packages with
`./gradlew :trackhub:publish` and consume `com.trackhub:trackhub-android:1.0.0`.)

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
