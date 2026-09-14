package com.trackhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalesEventParityTest {
    @Test
    fun onboardingDiscardsEveryPlacementLikeIosWithoutMutatingCallerData() {
        val input = mutableMapOf<String, Any>("placement_name" to "typo", "screen" to "welcome")
        for (placement in listOf(null) + TrackHubSalesPlacement.entries) {
            assertEquals(
                "ob_shown" to mapOf("screen" to "welcome"),
                TrackHub.salesEventPayload(TrackHubSalesEvent.ONBOARDING_SHOWN, placement, input),
            )
        }
        assertEquals("typo", input["placement_name"])
    }

    @Test
    fun paywallAndPurchaseIntentRequireTypedPlacementAndOverrideFreeFormValue() {
        for (event in listOf(TrackHubSalesEvent.PAYWALL_SHOWN, TrackHubSalesEvent.PURCHASE_CTA_TAPPED)) {
            assertNull(TrackHub.salesEventPayload(event, null, mapOf("placement_name" to "inapp_placement")))
            for (placement in TrackHubSalesPlacement.entries) {
                assertEquals(
                    event.value to mapOf("placement_name" to placement.value, "variant" to "A"),
                    TrackHub.salesEventPayload(event, placement, mapOf("placement_name" to "typo", "variant" to "A")),
                )
            }
        }
    }
}
