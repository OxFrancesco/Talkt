package org.buddytools.talkt.wakeword

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordAudioGateTest {
    @Test
    fun `silence does not run keyword inference`() {
        val gate = WakeWordAudioGate()

        repeat(50) {
            assertFalse(gate.accept(constantFrame(120)).shouldProcess)
        }
    }

    @Test
    fun `speech opens immediately and stays open for trailing blanks`() {
        val gate = WakeWordAudioGate(hangoverFrames = 4)

        assertTrue(gate.accept(constantFrame(2_500)).shouldProcess)
        repeat(4) {
            assertTrue(gate.accept(constantFrame(120)).shouldProcess)
        }
        val closed = gate.accept(constantFrame(120))
        assertFalse(closed.shouldProcess)
        assertTrue(closed.resetEngine)
    }

    @Test
    fun `room noise adapts without hiding a nearby voice`() {
        val gate = WakeWordAudioGate()

        repeat(20) { gate.accept(constantFrame(280)) }

        assertFalse(gate.accept(constantFrame(1_500)).shouldProcess)
        assertTrue(gate.accept(constantFrame(2_500)).shouldProcess)
    }

    private fun constantFrame(amplitude: Int): ShortArray =
        ShortArray(WakeWordModel.frameSamples) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
}
