package com.trackhub

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeCircuitTest {
    @After
    fun resetCircuit() {
        TrackHub.resetRuntimeCircuitForTest()
    }

    @Test
    fun recoverableAlgorithmFailureOpensCircuitWithoutEscaping() {
        assertFalse(TrackHub.runtimeCircuitOpenForTest())

        TrackHub.runFailSilentForTest {
            throw IllegalStateException("synthetic SDK algorithm failure")
        }

        assertTrue(TrackHub.runtimeCircuitOpenForTest())
    }

    @Test
    fun vmAndRuntimeErrorsAreNotHiddenByTheSdkCircuit() {
        assertThrows(AssertionError::class.java) {
            TrackHub.runFailSilentForTest {
                throw AssertionError("synthetic fatal error")
            }
        }
        assertFalse(TrackHub.runtimeCircuitOpenForTest())
    }
}
