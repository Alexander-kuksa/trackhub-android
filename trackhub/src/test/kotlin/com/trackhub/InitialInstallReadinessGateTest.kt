package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialInstallReadinessGateTest {
    @Test
    fun waitsForConfigAndReferrerInEitherOrder() {
        val configFirst = mutableListOf<String?>()
        val first = InitialInstallReadinessGate(configFirst::add) { error("unexpected late referrer: $it") }
        first.resolveRemoteConfig()
        assertTrue(configFirst.isEmpty())
        first.resolveReferrer("gclid=click-one")
        assertEquals(listOf("gclid=click-one"), configFirst)

        val referrerFirst = mutableListOf<String?>()
        val second = InitialInstallReadinessGate(referrerFirst::add) { error("unexpected late referrer: $it") }
        second.resolveReferrer("gclid=click-two")
        assertTrue(referrerFirst.isEmpty())
        second.resolveRemoteConfig()
        assertEquals(listOf("gclid=click-two"), referrerFirst)
    }

    @Test
    fun timeoutReleasesOnceAndPreservesLateReferrer() {
        val initial = mutableListOf<String?>()
        val late = mutableListOf<String>()
        val gate = InitialInstallReadinessGate(initial::add, late::add)

        gate.onTimeout()
        gate.resolveRemoteConfig()
        gate.resolveReferrer("wbraid=late-web-click")
        gate.resolveReferrer("wbraid=late-web-click")
        gate.onTimeout()

        assertEquals(listOf<String?>(null), initial)
        assertEquals(listOf("wbraid=late-web-click"), late)
    }

    @Test
    fun explicitOrganicReferrerStillWaitsForRemoteConfig() {
        val initial = mutableListOf<String?>()
        val gate = InitialInstallReadinessGate(initial::add) { error("unexpected late referrer: $it") }

        gate.resolveReferrer(null)
        assertTrue(initial.isEmpty())
        gate.resolveRemoteConfig()

        assertEquals(listOf<String?>(null), initial)
    }
}
