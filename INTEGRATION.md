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
