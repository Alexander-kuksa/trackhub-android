# TrackHub Android 3.0 integration reference

SDK 3 uses its durable `install_uid` as the internal measurement `user_id`.
Billing identities are optional provider-scoped bindings and never rename the
installation.

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

## First-party measurement geography (3.0.2)

A compatible production `/install` ACK includes `geo_ack_version: 1` and may
include ISO-3166 `country` and protective `eea` values resolved at Daively's
trusted edge. TrackHub validates them, scopes them to the current `install_uid`,
and durably caches them for later install, session, event, consent and
purchase-context payloads. This is a device fallback only; trusted-edge
geography is resolved again server-side for consent and external-delivery
policy.

Host `countryCode` is an optional initial fallback and may be replaced by a
later server result. EEA merges protectively: host true OR cached true remains
true. The SDK never uses Locale or GPS as geography and never discovers, stores
or sends an IP address.

Upgrades from 3.0.1 with an existing credential queue one idempotent `/install`
context refresh when the cache is missing. A `2xx` without
`geo_ack_version: 1` is an old server and retries with backoff. The durable job
retires after 12 retryable/old-contract responses and fails silently. A v1 ACK
without country/EEA is a valid terminal unknown result. The server must encode
the ACK version as the integer JSON token `1`, not `1.0` or a string, and must
omit unknown country/EEA fields instead of emitting explicit `null`.

Release gate: tag/publish 3.0.2 only after the platform contract, tests and
production deployment are verified with a live `/install` ACK. Server-first is
mandatory: after 12 old-contract responses the install-scoped terminal marker
does not re-arm when the server is deployed later. Bounded retry preserves
measurement safety but cannot recover that installation's device geo cache.

Public measurement, deep-link, consent, attribution, push-token, purchase
context and erasure methods remain asynchronous. Sessions coalesce for 30
minutes in background; a captured deep link can force a new session.

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
