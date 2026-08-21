# TrackHub Android 3.0 integration reference

SDK 3 uses its durable `install_uid` as the internal measurement `user_id`.
Billing identities are optional provider-scoped bindings and never rename the
installation.

Version 3.0.3 synchronously commits a newly generated `install_uid` before any
report may use it. Commit failure opens the process-local storage circuit, so a
hard kill cannot turn an acknowledged installation into a second identity on
the next launch.

```kotlin
TrackHub.start(applicationContext, TrackHubConfig(sdkKey = trackHubSdkKey))
TrackHub.setExternalIdentity("apphud", Apphud.userId())
// or
TrackHub.setExternalIdentity("revenuecat", Purchases.sharedInstance.appUserID)
```

The host owns all billing imports and calls. TrackHub contains no Apphud /
RevenueCat dependency, reflection or version probing. A provider logout is
`setExternalIdentity(provider, null)` and affects no other provider.
The desired identity is persisted immediately, but production delivery waits
for the install acknowledgement. Test Lab remains independent. Version 3.0.1
also lets a queued production install pass a blocked 3.0.0 identity head.

## Owner-controlled Android identifiers (3.0.4)

GAID and App Set ID collection is off by default and controlled per app by an
owner-only switch in Daively. The SDK fetches that setting asynchronously, so
install delivery never waits for control-plane I/O. When enabled, it reads GAID
and falls back to App Set ID, then backfills an already-acknowledged install.
The setting refreshes on launch and foreground (at most once per minute), which
also provides a remote kill switch without another app release.

The Google runtimes and `AD_ID` permission ship with 3.0.4 so enabling the
switch is effective on released builds. Missing or non-functional Google Play
Services fail soft. Consent fields remain truthful metadata but do not block
identifier collection after the owner enables it. A host may still set
`collectAdvertisingId = false` as a local emergency opt-out; it cannot override
the server's default-off gate in the enabling direction.

## Server-owned measurement geography (3.0.3)

The SDK does not learn or cache the server's geography result. On every
official SDK request Daively resolves current geography from a trusted edge
country header or its local country-only GeoIP database. Only when those are
unavailable does the server use the optional host `countryCode`, followed by
the stored installation country for later reports. Manual S2S requests skip
request-IP GeoIP so a backend location cannot become the user's country.

The SDK never uses Locale or GPS as geography and never discovers, stores or
sends an IP address. Host EEA/consent signals remain request inputs, but
destination policy is enforced on the server. The install response may contain
the integer `geo_ack_version: 1` compatibility marker for already-published
3.0.2 clients; it never contains `country` or `eea`, and 3.0.3 ignores it.

When upgrading from 3.0.2, the SDK deletes the retired install-scoped geography
cache and removes any queued `install_geo_refresh` report before delivery.

## Consent defaults (3.0.3)

`TrackHubGoogleAdsConsent()` defaults both signals to `UNKNOWN`, and the public
consent API is optional for a basic integration. TrackHub never presents an
additional Google consent UI. Daively fills missing signals with `GRANTED`
globally, including confirmed EEA and unknown geography. An explicit CMP value
always wins. Confirmed-EEA and unknown-geo grants are observable server-side
and have separate operator kill switches. Advertising-ID availability never
changes either Google consent value; the independent server default does.

Public measurement, deep-link, consent, attribution, push-token, purchase
context and erasure methods remain asynchronous. Sessions coalesce for 30
minutes in background; a captured deep link can force a new session.

## Optional engagement-event deduplication

```kotlin
TrackHub.trackEvent("tutorial_done", deduplicationId = "tutorial-v1")
```

Blank keys behave as absent, while nonblank keys over 256 UTF-8 bytes skip the
event. iOS and Android derive `client_event_id` as `dedup1-` plus lowercase
SHA-256 of
`install_uid + NUL + event_name + NUL + trimmed_deduplication_id`. The scope is
one installation and event name: a repeated host call on that installation is
idempotent, while another installation cannot be suppressed. Omitting the key
keeps the existing random event ID behavior. Shared fixture:

```text
install_uid: 11111111-2222-4333-8444-555555555555
event_name: tutorial_done
deduplication_id: order-42
client_event_id: dedup1-9068017e11119b7a3c99163c1cb825e87ecda0542a4405cb526d506b941eb579
```

Deduplication lasts for the measurement-event retention window: 90 days by
default, configurable at account level, and indefinite when retention is `0`.

Money rules match the server contract: provider-only revenue is allowed, store
verification is an optional trust upgrade, transaction-family ownership beats
device activity, and explicit Family Sharing does not count or forward.

Release verification:

1. Confirm dependency resolution contains no billing SDK through TrackHub.
2. Run a clean Test Lab install and verify install precedes session.
3. Exercise provider anonymous ID, login, logout and restore.
4. Verify Apphud 3.6.2 (QR Scanner canary) compiles without TrackHub conflicts.
5. Test 29:59/30:01 session boundaries and forced deep-link session.
6. Test a sandbox purchase plus authenticated provider webhook.
7. Test airplane mode, process death and exactly-once queue drain.
8. Test offline `gdprForgetMe` and relaunch.
9. Confirm SDK Key, install credential and external IDs are absent from logs.

SDK 3 is a clean break made before commercial integrations. Native SDK 2.x
reports are intentionally rejected rather than maintained as a second contract.
