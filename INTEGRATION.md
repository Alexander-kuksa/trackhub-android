# TrackHub Android SDK — подробное руководство по внедрению

> **Версия SDK:** `1.6.2` · **minSdk:** 21 · **compileSdk:** 34
> **Статус:** production integration guide, сверен с публичным API 7 августа 2026 года
> Общая архитектура: [TrackHub SDK Integration Guide](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/SDK_INTEGRATION_GUIDE.md)
> HTTP-контракт: [SDK Wire Contract](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/SDK_CONTRACT.md)

Этот документ можно передать Android-разработчику как самостоятельное техническое задание. Он
покрывает установку зависимости, порядок инициализации, Apphud, consent, Google identifiers,
install referrer, deep links, события, покупки, FCM, backend callbacks, Test Lab и production
release.

## 1. Результат интеграции

После подключения SDK приложение автоматически отправляет:

- install report при первой подтверждённой установке;
- foreground sessions с 60-секундным coalescing;
- product engagement events;
- Google Play Install Referrer и поддерживаемые click references;
- transaction identity и device context после подтверждённой покупки;
- FCM token для uninstall measurement, если контур включён;
- Consent Mode и PIPL flags;
- attribution в Apphud через host/backend adapter.

SDK не принимает price/currency/revenue. Финансовый источник истины — Apphud/S2S.

## 2. Требования

- Android API 21+;
- Kotlin или Java consumer app;
- JDK 17+ для сборки AGP 8.5.x;
- TrackHub app с platform `android`;
- актуальные `endpoint`, `ingestToken`, `sdkSecret` из TrackHub Setup;
- Apphud custom user id, если приложение использует Apphud;
- Play-enabled физическое устройство для финального Install Referrer smoke test;
- backend приложения для user-level attribution и privacy erasure;
- FCM connection в TrackHub, если нужен uninstall measurement.

## 3. Добавление зависимости

В `settings.gradle.kts` consumer app добавьте JitPack:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

В `app/build.gradle.kts` закрепите release tag:

```kotlin
dependencies {
    implementation("com.github.Alexander-kuksa:trackhub-android:1.6.2")
}
```

Не используйте moving branch, commit snapshot или `-SNAPSHOT` в production build.

Базовая SDK подтягивает Google Play Install Referrer. Advertising ID и App Set ID намеренно
не являются обязательными runtime dependencies. Если identifier collection разрешён продуктом и
consent, добавьте:

```kotlin
dependencies {
    implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
    implementation("com.google.android.gms:play-services-appset:16.1.0")
}
```

Версия ads identifier `18.2.0` сохраняет minSdk 21. Обновляя её, отдельно проверьте новый minSdk.

После Gradle sync убедитесь, что dependency разрешилась именно в `1.6.2`:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Если consumer repository не содержит Gradle wrapper, используйте Gradle 8.7 или Android Studio.

## 4. Manifest и permissions

Для обычной интеграции нужен Internet permission consumer app:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

SDK не запрашивает notification permission и не добавляет Firebase. Host app управляет этими
интеграциями самостоятельно.

Если `collectAdvertisingId=false`, merged manifest не должен неожиданно содержать `AD_ID`:

```bash
./gradlew :app:processReleaseMainManifest
```

Проверьте итоговый manifest в `app/build/intermediates/merged_manifests/...`. Если host app или
другая dependency добавляет AD_ID, privacy declaration должна учитывать весь dependency graph,
а не только TrackHub.

Дополнительные ProGuard/R8 правила для публичного Kotlin API TrackHub обычно не нужны: library
поставляет consumer rules. После minified release всё равно выполните smoke test вызова
`TrackHub.configure` и Apphud callbacks.

## 5. Получение app-specific значений

Откройте TrackHub → Apps → нужное Android-приложение → Setup → SDK integration и скопируйте:

- `endpoint`: только origin, например `https://postbacks.daively.com`;
- `ingestToken`: token именно этого приложения;
- `sdkSecret`: app-specific secret, если SDK Signature включена;
- Test Lab token: только для временной QA-сборки.

Не передавайте endpoint вида:

```text
https://postbacks.daively.com/ingest/<token>/install
```

SDK сама строит endpoint paths. Правильное значение:

```text
https://postbacks.daively.com
```

`sdkSecret` находится в mobile binary и не заменяет backend S2S token. `X-TrackHub-Token` никогда
не должен попадать в APK, BuildConfig, Firebase Remote Config или mobile API response.

## 6. Рекомендуемая структура host app

Создайте один bootstrap-объект, чтобы параметры и порядок не расходились между Activities:

```kotlin
object TrackHubBootstrap {
    fun configure(context: Context, testLabToken: String? = null) {
        TrackHub.configure(
            context = context.applicationContext,
            endpoint = BuildConfig.TRACKHUB_ENDPOINT,
            ingestToken = BuildConfig.TRACKHUB_INGEST_TOKEN,
            userId = Apphud.userId(),
            sdkSecret = BuildConfig.TRACKHUB_SDK_SECRET,
            debug = BuildConfig.DEBUG,
            integrationTestToken = testLabToken,
            collectAdvertisingId = ConsentStore.adUserDataAllowed(context),
            countryCode = MeasurementCountry.current(context),
            apphudCollectDeviceIdentifiersHandler = {
                Apphud.collectDeviceIdentifiers()
            },
            backendAttributionProvider = { userId, completion ->
                AppBackend.fetchTrackHubAttribution(userId, completion)
            },
            backendPrivacyErasureHandler = { userId, reason, completion ->
                AppBackend.eraseTrackHubUser(userId, reason, completion)
            },
            apphudAttributionHandler = { data, completion ->
                ApphudBridge.sendCustomAttribution(data, completion)
            },
            attributionChangedHandler = { attribution ->
                AttributionStore.update(attribution)
            },
            deferredDeepLinkHandler = { path ->
                path?.let(AppRouter::routeTrackHubPath)
            },
        )
    }
}
```

`AppBackend`, `ApphudBridge`, `ConsentStore`, `MeasurementCountry` и `AppRouter` — компоненты host
app, не классы TrackHub SDK. Их названия в примере намеренно описательные.

Если приложение вызывает `configure` повторно, SDK остаётся безопасной. Тем не менее держите
конфигурацию в одном bootstrap-компоненте, чтобы callbacks и credentials имели очевидное место
владения и не расходились между Activities/build variants.

## 7. Порядок cold start

### 7.1 Базовый вариант через `Application`

Подходит большинству приложений:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Восстановить уже принятое consent state.
        TrackHub.setGoogleAdsConsent(
            context = this,
            adUserData = ConsentStore.adUserDataAllowed(this),
            adPersonalization = ConsentStore.adPersonalizationAllowed(this),
            eea = ConsentStore.isEeaUser(this),
        )

        // 2. Mainland China only.
        if (ConsentStore.isMainlandChinaUser(this)) {
            TrackHub.setPiplConsent(
                context = this,
                piplConsent = ConsentStore.piplAllowed(this),
                crossBorderTransferConsent = ConsentStore.crossBorderAllowed(this),
                adsMeasurementConsent = ConsentStore.adsMeasurementAllowed(this),
            )
        }

        // 3. Apphud first: TrackHub должен получить тот же stable custom user id.
        Apphud.start(/* current Apphud API/config */)

        // 4. TrackHub second.
        TrackHubBootstrap.configure(this)
    }
}
```

SDK `configure` non-blocking: preference reads, migration, queue и сеть работают вне main thread.
`trackEvent` сразу после `configure` будет поставлен за initialization на том же serial executor и
не потеряется.

### 7.2 Cold deep link до `configure`

Если критично включить `gclid`/`gbraid`/`oppref` из launch Intent в самый ранний report, сначала
сохраните URL context overload, затем вызывайте bootstrap из launcher Activity:

В этом варианте Apphud и consent state по-прежнему подготавливаются в `Application`, но
`TrackHubBootstrap.configure(this)` из предыдущего примера там не вызывается: первая TrackHub
конфигурация переносится в launcher Activity после capture URL.

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.data?.let { uri ->
            TrackHub.handleDeepLink(applicationContext, uri)
        }

        TrackHubBootstrap.configure(applicationContext)
        setContentView(R.layout.activity_main)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            if (TrackHub.handleDeepLink(applicationContext, uri)) {
                AppRouter.routeVisibleUri(uri)
            }
        }
    }
}
```

Если Apphud запускается в `Application`, его user id уже будет доступен launcher Activity.
Обычная fresh install attribution Google Play приходит через Install Referrer; launch Intent
особенно важен для уже установленного приложения и ChatGPT/Google re-engagement links.

Не вызывайте query-parameter APIs на `mailto:`, `tel:` и других opaque URI самостоятельно без
проверки. TrackHub `handleDeepLink` fail-open и вернёт `false`, но host router тоже должен быть
устойчивым.

## 8. Полный контракт `configure`

| Параметр | Обязателен | Что передавать |
|---|---:|---|
| `context` | да | `applicationContext` |
| `endpoint` | да | HTTPS origin без `/ingest` |
| `ingestToken` | да | app-specific token |
| `userId` | рекомендуется | тот же stable custom id, что Apphud |
| `sdkSecret` | для signed app/purchase | app SDK secret, не S2S token |
| `firebaseAppInstanceId` | нет | GA4 join key, если Firebase используется |
| `debug` | только QA | безопасные `[TrackHub]` status logs |
| `integrationTestToken` | только QA | short-lived Test Lab token 20–128 chars |
| `collectAdvertisingId` | opt-in | `true` только после `ad_user_data` consent |
| `apphudCollectDeviceIdentifiersHandler` | рекомендуется с Apphud | вызов official Apphud collection API |
| `backendAttributionProvider` | для attribution read | host backend proxy, возвращающий raw JSON |
| `backendPrivacyErasureHandler` | для erase | host backend proxy, возвращающий `true` только после TrackHub 2xx |
| `apphudAttributionHandler` | для Apphud bridge | adapter custom attribution + acknowledgement |
| `attributionChangedHandler` | нет | обновление app state/UI на main thread |
| `deferredDeepLinkHandler` | для owned links | routing allowlisted path |
| `countryCode` | рекомендуется | фактический ISO-3166 alpha-2 country, не Locale language |

SDK валидирует HTTPS. HTTP разрешён только для `localhost`, `127.0.0.1` и `::1` в локальной
разработке.

## 9. Consent и Advertising ID

Передавайте Consent Mode до `configure` и при каждом изменении:

```kotlin
fun onAdsConsentChanged(context: Context, state: AdsConsentState) {
    TrackHub.setGoogleAdsConsent(
        context = context.applicationContext,
        adUserData = state.adUserData,
        adPersonalization = state.adPersonalization,
        eea = state.eea,
    )
}
```

Правила:

- `collectAdvertisingId=true` не является consent;
- SDK дополнительно проверяет сохранённый `ad_user_data` перед чтением GAID;
- при denied consent не включайте identifier collection в новом configure;
- Locale/язык не определяют EEA и country;
- после изменения consent SDK повторно сообщает latest flags, не переписывая attribution snapshot.

Для mainland-China пользователя:

```kotlin
TrackHub.setPiplConsent(
    context = applicationContext,
    piplConsent = state.personalInformationProcessing,
    crossBorderTransferConsent = state.crossBorderTransfer,
    adsMeasurementConsent = state.adsMeasurement,
)
```

Не объединяйте три PIPL решения в один checkbox на уровне SDK contract.

## 10. Apphud identity и callbacks

### 10.1 Один user id

Вызовите Apphud первым и передайте TrackHub тот же custom user id. Если account id появляется
после login:

```kotlin
fun onAuthenticatedUserResolved(stableUserId: String) {
    ApphudBridge.updateUser(stableUserId)
    TrackHub.setUserId(stableUserId)
}
```

Лучше иметь правильный id до первого `configure`: `setUserId` не должен использоваться как
обычный способ исправлять неверно созданный install.

### 10.2 Attribution provider

`backendAttributionProvider` должен:

1. вызвать authenticated endpoint host backend;
2. не отправлять туда S2S token с клиента;
3. получить raw TrackHub JSON;
4. вызвать completion ровно один раз;
5. вернуть `null` при transport/auth/parse error.

Пример интерфейса host app:

```kotlin
object AppBackend {
    fun fetchTrackHubAttribution(userId: String, completion: (String?) -> Unit) {
        // Backend сам сверяет userId с authenticated session.
        api.trackHubAttribution(
            onSuccess = { rawJson -> completion(rawJson) },
            onFailure = { completion(null) },
        )
    }

    fun eraseTrackHubUser(
        userId: String,
        reason: String,
        completion: (Boolean) -> Unit,
    ) {
        api.eraseTrackHubUser(
            reason = reason,
            onSuccess = { completion(true) },
            onFailure = { completion(false) },
        )
    }
}
```

SDK вызывает host handlers на main thread и ограничивает зависший callback watchdog в 15 секунд.
Не выполняйте blocking network call внутри handler.

### 10.3 Apphud custom attribution

`apphudAttributionHandler` получает `Map<String, String>` без raw click ids. Передайте его в
официальный custom-attribution API используемой версии Apphud и вызовите completion `true` только
после Apphud acknowledgement. TrackHub сохраняет revision после `true`; новый revision будет
доставлен повторно.

## 11. Attribution для UI

Получить текущий durable snapshot:

```kotlin
TrackHub.getAttribution { attribution ->
    if (attribution == null) {
        // Нет подтверждённого snapshot или backend недоступен.
        return@getAttribution
    }

    analytics.setAcquisitionChannel(attribution.channel)
    attribution.campaignId?.let(analytics::setCampaignId)
}
```

Не превращайте `null` в «organic»: это может быть временная недоступность backend. Поле `status`
в успешном snapshot различает attributed/unattributed semantics.

Принудительный повтор Apphud bridge:

```kotlin
TrackHub.refreshApphudAttribution()
```

Обычно он не нужен: configure, install/session success и `setUserId` уже запускают refresh.

## 12. Funnel и custom events

Android SDK предоставляет общий typed helper:

```kotlin
TrackHub.trackSalesEvent(TrackHubSalesEvent.ONBOARDING_SHOWN)

TrackHub.trackSalesEvent(
    event = TrackHubSalesEvent.PAYWALL_SHOWN,
    placement = TrackHubSalesPlacement.ONBOARDING,
)

TrackHub.trackSalesEvent(
    event = TrackHubSalesEvent.PURCHASE_CTA_TAPPED,
    placement = TrackHubSalesPlacement.ONBOARDING,
    callbackParams = mapOf("offer_id" to "annual_intro"),
)
```

Точные trigger boundaries:

- onboarding shown — когда UI действительно виден;
- paywall shown — после presentation, не при prefetch config;
- purchase CTA — непосредственно перед запуском Billing flow;
- успешная покупка — отдельный `trackPurchaseObserved`, не CTA event.

Custom event:

```kotlin
TrackHub.trackEvent(
    name = "tutorial_completed",
    callbackParams = mapOf(
        "step_count" to 7,
        "variant" to "short",
        "skipped" to false,
    ),
    partnerParams = mapOf("internal_experiment" to "exp_42"),
)
```

Используйте только JSON-compatible bounded значения. Не передавайте `Double.NaN`,
`Double.POSITIVE_INFINITY`, Context/View/Throwable, tokens, PII или деньги. Максимальный payload —
64 KiB.

## 13. Purchase bridge с Play Billing

Вызов делается после подтверждённой покупки:

```kotlin
fun onPurchaseConfirmed(purchase: Purchase) {
    val transactionId = purchase.orderId
    if (transactionId.isNullOrBlank()) {
        // Не заменяйте orderId случайным UUID. Зафиксируйте кейс и дождитесь
        // стабильной transaction identity, которую увидит Apphud.
        return
    }

    TrackHub.trackPurchaseObserved(
        transactionId = transactionId,
        productId = purchase.products.firstOrNull(),
    )
}
```

Важно:

- `sdkSecret` обязателен;
- amount/currency не передаются;
- pending/cancelled/failed purchase не отправляется;
- retry делает SDK, host app не вызывает TrackHub HTTP напрямую;
- Apphud webhook должен содержать ту же transaction identity;
- purchase context живёт максимум 72 часа и удаляется после join.

## 14. Deep links и deferred routing

`handleDeepLink` извлекает Google `gclid`/`gbraid` и OpenAI Ads `oppref`. Host app остаётся
владельцем обычного navigation routing.

Deferred deep link flow:

1. TrackHub measurement link записывает click и создаёт opaque match token.
2. Google Play переносит token в Install Referrer.
3. SDK durably ставит install в queue.
4. TrackHub подтверждает install HTTP `2xx`.
5. Только после ACK SDK вызывает `/resolve`.
6. Server атомарно consumes click и возвращает opaque `deep_link_path` или `null`.
7. Host app проверяет path allowlist и выполняет navigation.

Дополнительный явный запрос:

```kotlin
TrackHub.resolveDeferredDeepLink { path ->
    path?.takeIf(AppRouter::isAllowedTrackHubPath)
        ?.let(AppRouter::routeTrackHubPath)
}
```

Если запрос сделан до install ACK, SDK запоминает callback и выпускает его после ACK. Path
возвращается максимум один раз. Не используйте его как authorization для sensitive screen — это
navigation hint, а не user credential.

## 15. FCM token и uninstall measurement

TrackHub не инициализирует Firebase. В существующем service:

```kotlin
class MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        TrackHub.setPushToken(applicationContext, token)
    }
}
```

После `configure` SDK повторно отправляет последний сохранённый token. Свяжите FCM service
account/connection с тем же app в TrackHub. Notification permission Android 13 не требуется для
самого data-only uninstall probe, но host app отвечает за свою notification policy.

Uninstall учитывается только при явном FCM `UNREGISTERED`, не при timeout или generic error.

## 16. Optional Firebase/GA4 join

TrackHub не требует Firebase. Если приложение уже использует GA4 forwarding, передайте
`app_instance_id` до первого install report:

```kotlin
FirebaseAnalytics.getInstance(applicationContext).appInstanceId
    .addOnSuccessListener { appInstanceId ->
        if (!appInstanceId.isNullOrBlank()) {
            TrackHub.setFirebaseAppInstanceId(appInstanceId)
        }
        TrackHubBootstrap.configure(applicationContext)
    }
    .addOnFailureListener {
        // Measurement не должен зависнуть из-за optional Firebase value.
        TrackHubBootstrap.configure(applicationContext)
    }
```

Не задерживайте launch бесконечно. Используйте bounded host timeout, если Firebase task может
выполняться долго.

## 17. Privacy erasure

После подтверждения пользователем:

```kotlin
TrackHub.forgetDevice(reason = "user_requested") { accepted ->
    if (accepted) {
        privacyUi.showCompleted()
    } else {
        privacyUi.showRetryableError()
    }
}
```

`true` означает, что host backend подтвердил успешный TrackHub erase. После этого SDK удаляет
queue/identifiers и блокирует дальнейший tracking для текущего app token. Reconfigure не включает
tracking обратно. Для нового законного identity lifecycle требуется отдельное продуктовое решение,
а не очистка SharedPreferences вручную.

## 18. Работа при outage

SDK `1.6.2`:

- пишет report в `AtomicFile` под `noBackupFilesDir` до network delivery;
- хранит максимум 1 000 reports / 4 MiB;
- ограничивает один payload 64 KiB;
- использует один последовательный delivery worker;
- повторяет transport errors, `408`, `429`, `5xx`;
- использует exponential backoff с jitter до 5 минут;
- сохраняет production install при вытеснении обычных событий;
- исправляет `401 clock_skew` через process-local signing offset;
- quarantine'ит corrupt/oversized recovery file;
- не пробрасывает сетевые исключения в host app;
- ограничивает host callbacks 15 секундами.

Остальные `4xx` считаются permanent contract/config error и не повторяются бесконечно. Исправьте
endpoint/token/secret/payload и выпустите корректную сборку.

Не добавляйте собственную параллельную очередь и не ждите delivery через `runBlocking`, latch,
semaphore или main-thread future.

## 19. Integration Test Lab

Создайте run в TrackHub и передайте token только QA build:

```kotlin
TrackHub.configure(
    context = applicationContext,
    endpoint = BuildConfig.TRACKHUB_ENDPOINT,
    ingestToken = BuildConfig.TRACKHUB_INGEST_TOKEN,
    userId = Apphud.userId(),
    sdkSecret = BuildConfig.TRACKHUB_SDK_SECRET,
    debug = true,
    integrationTestToken = BuildConfig.TRACKHUB_TEST_RUN_TOKEN,
)
```

Проверьте timeline:

- SDK version `1.6.2`;
- signature accepted;
- install + install UID;
- session;
- custom/funnel event;
- consent/device context;
- purchase context;
- Apphud event и transaction join;
- deferred link при наличии;
- отсутствие production provider send в shadow mode.

Test Lab namespace отделён от production queue. Новый run используйте для каждого чистого теста.
Перед release убедитесь, что `TRACKHUB_TEST_RUN_TOKEN` отсутствует или `null` во всех production
variants.

## 20. Локальная и CI-проверка SDK

В SDK repository:

```bash
gradle :trackhub:test
gradle :trackhub:lint
gradle :trackhub:assembleRelease
gradle :trackhub:assembleDebugAndroidTest
gradle :trackhub:connectedDebugAndroidTest
```

Последняя команда требует API 34 emulator/device. Отдельно на Play-enabled устройстве проверьте
Install Referrer: обычный AOSP emulator не воспроизводит все Play Store states.

В consumer app дополнительно выполните:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lint
```

Проверьте minified release, merged manifest, cold start и отсутствие S2S token через APK analyzer.

## 21. Troubleshooting

| Симптом | Что проверить |
|---|---|
| SDK logs отсутствуют | `debug=true`, вызов `configure`, правильный process |
| `refusing non-HTTPS endpoint` | передан HTTPS origin, без пробелов/path |
| install отсутствует | clean install, token, signature, Test Lab namespace |
| session/event есть, install нет | старый local install state или permanent install rejection |
| `401` | app token, SDK secret, signature setting, clock-skew response |
| `429` | server rate limit; SDK повторит с backoff |
| event rejected | JSON types, 64 KiB limit, event name |
| purchase не join'ится | order/transaction id не совпадает с Apphud |
| deferred path `null` | no token, уже consumed, >48h, install ещё не ACK |
| attribution `null` | backend auth/network или ещё нет snapshot; не считать автоматически organic |
| Apphud attribution повторяется | host callback не возвращает `true` после acknowledgement |
| UI зависает | host callback/blocking wrapper; SDK API не надо ждать синхронно |
| очередь растёт | endpoint health, `5xx/429`, DNS/TLS, Data Health |
| GAID отсутствует | dependency, consent, `collectAdvertisingId`, limited tracking |

Полная диагностика: [TROUBLESHOOTING.md](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/TROUBLESHOOTING.md).

## 22. Production checklist

- [ ] Dependency закреплена на `1.6.2`.
- [ ] `minSdk` и release build успешны.
- [ ] Endpoint — production HTTPS origin.
- [ ] Token/secret принадлежат правильному Android app.
- [ ] S2S token отсутствует в APK и logs.
- [ ] Apphud стартует до TrackHub и использует тот же user id.
- [ ] Consent deny/allow проверены.
- [ ] `collectAdvertisingId=false` не добавляет AD_ID неожиданно.
- [ ] Cold Intent и `onNewIntent` обрабатываются.
- [ ] Install Referrer проверен на Play-enabled устройстве.
- [ ] Funnel helpers стоят на реальных UI boundaries.
- [ ] Purchase observation использует Apphud-compatible transaction id.
- [ ] FCM token rotation проверена, если uninstall включён.
- [ ] Backend attribution/erase требуют authenticated user.
- [ ] Offline/timeout сценарий не блокирует UI.
- [ ] Unit, lint, release AAR и instrumentation зелёные.
- [ ] Shadow Test Lab пройден.
- [ ] Live canary согласован отдельно.
- [ ] Test Lab token удалён из production variant.

## 23. Upgrade с предыдущих версий

При обновлении до `1.6.2`:

1. не очищайте app data или TrackHub preferences;
2. не меняйте одновременно ingest token, SDK secret и Apphud user id;
3. SDK атомарно мигрирует legacy queue из SharedPreferences в `AtomicFile`;
4. сохраните `noBackupFilesDir` между обычными app upgrades;
5. сначала разверните backward-compatible server, затем mobile release;
6. проверьте `sdk_version=1.6.2` в Test Lab;
7. выполните staged rollout и наблюдайте rejected events/Data Health;
8. старый SDK secret удаляйте только после adoption и server grace period.

## 24. Связанные документы

- [Android SDK README](README.md)
- [Общий SDK integration guide](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/SDK_INTEGRATION_GUIDE.md)
- [Wire contract](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/SDK_CONTRACT.md)
- [Google Ads runbook](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/INTEGRATION_GOOGLE_ADS.md)
- [Firebase import](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/INTEGRATION_FIREBASE_IMPORT.md)
- [Troubleshooting](https://github.com/Alexander-kuksa/trackhub/blob/main/docs/TROUBLESHOOTING.md)
