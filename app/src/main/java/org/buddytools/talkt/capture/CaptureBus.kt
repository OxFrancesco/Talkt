package org.buddytools.talkt.capture

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object CaptureBus {
    private val stopRequested = AtomicBoolean(false)
    private val finishPowerSession = AtomicReference<(() -> Unit)?>(null)

    fun requestStop() {
        stopRequested.set(true)
    }

    fun consumeStopRequest(): Boolean = stopRequested.getAndSet(false)

    fun bindPowerSession(onFinish: () -> Unit) {
        finishPowerSession.getAndSet(onFinish)?.invoke()
    }

    fun finishPowerSession() {
        finishPowerSession.getAndSet(null)?.invoke()
    }
}
