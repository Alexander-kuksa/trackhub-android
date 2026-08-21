package com.trackhub

/**
 * Joins the two asynchronous inputs needed by the first Android install:
 * Daively's owner configuration and Google Play Install Referrer.
 *
 * The host app never waits on this gate. Only the SDK's durable first-install
 * enqueue is held, and [onTimeout] releases it with the best data available.
 */
internal class InitialInstallReadinessGate(
    private val onInitialReady: (String?) -> Unit,
    private val onLateReferrer: (String) -> Unit,
) {
    private val lock = Any()
    private var remoteConfigResolved = false
    private var referrerResolved = false
    private var initialDispatched = false
    private var initialReferrer: String? = null
    private var lastLateReferrer: String? = null

    fun resolveRemoteConfig() {
        val initial = synchronized(lock) {
            remoteConfigResolved = true
            takeInitialIfReady(force = false)
        }
        if (initial != null) onInitialReady(initial.value)
    }

    fun resolveReferrer(referrer: String?) {
        var initial: InitialAction? = null
        var late: String? = null
        synchronized(lock) {
            if (!referrerResolved) referrerResolved = true
            if (!referrer.isNullOrBlank()) initialReferrer = referrer
            if (!initialDispatched) {
                initial = takeInitialIfReady(force = false)
            } else if (
                !referrer.isNullOrBlank()
                && referrer != initialReferrerAtDispatch
                && referrer != lastLateReferrer
            ) {
                lastLateReferrer = referrer
                late = referrer
            }
        }
        initial?.let { onInitialReady(it.value) }
        late?.let(onLateReferrer)
    }

    fun onTimeout() {
        val initial = synchronized(lock) { takeInitialIfReady(force = true) }
        if (initial != null) onInitialReady(initial.value)
    }

    private var initialReferrerAtDispatch: String? = null

    private fun takeInitialIfReady(force: Boolean): InitialAction? {
        if (initialDispatched || (!force && (!remoteConfigResolved || !referrerResolved))) return null
        initialDispatched = true
        initialReferrerAtDispatch = initialReferrer
        return InitialAction(initialReferrer)
    }

    private data class InitialAction(val value: String?)
}
