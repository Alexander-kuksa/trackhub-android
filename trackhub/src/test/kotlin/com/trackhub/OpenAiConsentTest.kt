package com.trackhub

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises production persistence/serialization with isolated prefs, never a configured SDK. */
class OpenAiConsentTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        private val pending = mutableMapOf<String, Any?>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putString" -> { pending[args!![0] as String] = args[1]; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply", "commit" -> {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    pending.clear()
                    if (method.name == "commit") true else null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getBoolean", "getInt", "getString" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> values.toMap()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    private fun persist(store: MemoryPreferences, consent: TrackHubOpenAiAdsConsent, forceRevision: Boolean = true) {
        val method = TrackHub::class.java.getDeclaredMethod("applyOpenAiAdsConsent",
            SharedPreferences::class.java, SharedPreferences.Editor::class.java, TrackHubOpenAiAdsConsent::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        (method.invoke(TrackHub, store.prefs, store.prefs.edit(), consent, forceRevision) as SharedPreferences.Editor).apply()
    }

    private fun wire(store: MemoryPreferences): JSONObject {
        val method = TrackHub::class.java.getDeclaredMethod("appendConsent", SharedPreferences::class.java, JSONObject::class.java)
        method.isAccessible = true
        return JSONObject().also { method.invoke(TrackHub, store.prefs, it) }
    }

    @Test fun omittedOpenAiPermissionNeverInheritsGrantedGoogleConsent() {
        val config = TrackHubConfig("redacted-fixture", googleAdsConsent = TrackHubGoogleAdsConsent(
            TrackHubConsentStatus.GRANTED, TrackHubConsentStatus.GRANTED, false))
        assertEquals(TrackHubOpenAiAdsConsent(), config.openAiAdsConsent)
        assertEquals(TrackHubConsentStatus.UNKNOWN, config.openAiAdsConsent.measurement)
        assertEquals(TrackHubConsentStatus.UNKNOWN, config.openAiAdsConsent.userData)
        assertEquals(TrackHubConsentStatus.UNKNOWN, config.openAiAdsConsent.personalization)
    }

    @Test fun explicitTriStateSnapshotSurvivesPersistenceAndWireEncoding() {
        val store = MemoryPreferences()
        persist(store, TrackHubOpenAiAdsConsent(TrackHubConsentStatus.GRANTED,
            TrackHubConsentStatus.DENIED, TrackHubConsentStatus.UNKNOWN))
        val body = wire(store)
        assertTrue(body.getBoolean("openai_ads_measurement_consent"))
        assertFalse(body.getBoolean("openai_ads_user_data_consent"))
        assertTrue(body.has("openai_ads_personalization_consent"))
        assertTrue(body.isNull("openai_ads_personalization_consent"))
        assertEquals(1, body.getInt("openai_ads_consent_revision"))
        assertFalse(body.has("ad_user_data"))
    }

    @Test fun unknownClearsPriorGrantsAndAdvancesRevisionWithoutMutatingGoogle() {
        val store = MemoryPreferences()
        store.values["consent_ad_user_data"] = true
        persist(store, TrackHubOpenAiAdsConsent(TrackHubConsentStatus.GRANTED,
            TrackHubConsentStatus.GRANTED, TrackHubConsentStatus.GRANTED))
        val original = wire(store)
        persist(store, TrackHubOpenAiAdsConsent())
        val revoked = wire(store)
        for (field in listOf("measurement", "user_data", "personalization")) {
            assertTrue(original.getBoolean("openai_ads_${field}_consent"))
            assertTrue(revoked.has("openai_ads_${field}_consent"))
            assertTrue(revoked.isNull("openai_ads_${field}_consent"))
        }
        assertEquals(2, revoked.getInt("openai_ads_consent_revision"))
        assertEquals(true, store.values["consent_ad_user_data"])
    }

    @Test fun revisionSaturatesAtPositiveSignedIntegerLimit() {
        val store = MemoryPreferences()
        store.values["consent_openai_revision"] = Int.MAX_VALUE
        persist(store, TrackHubOpenAiAdsConsent(measurement = TrackHubConsentStatus.DENIED))
        assertEquals(Int.MAX_VALUE, wire(store).getInt("openai_ads_consent_revision"))
        assertFalse(wire(store).getBoolean("openai_ads_measurement_consent"))
    }

    @Test fun cachedStartupDoesNotFabricateNewConsentButExplicitUpdateAdvancesIt() {
        val store = MemoryPreferences()
        val granted = TrackHubOpenAiAdsConsent(measurement = TrackHubConsentStatus.GRANTED)
        persist(store, granted, forceRevision = false)
        assertEquals(1, wire(store).getInt("openai_ads_consent_revision"))
        persist(store, granted, forceRevision = false)
        assertEquals(1, wire(store).getInt("openai_ads_consent_revision"))
        persist(store, TrackHubOpenAiAdsConsent(), forceRevision = false)
        assertEquals(2, wire(store).getInt("openai_ads_consent_revision"))
        persist(store, TrackHubOpenAiAdsConsent(), forceRevision = true)
        assertEquals(3, wire(store).getInt("openai_ads_consent_revision"))
    }
}
