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
    fun `room noise adapts without hiding a distant voice`() {
        val gate = WakeWordAudioGate()

        repeat(20) { gate.accept(constantFrame(280)) }

        // Floor ~280, so anything under 2.2x the room stays gated; a far voice at 700 gets through.
        assertFalse(gate.accept(constantFrame(500)).shouldProcess)
        val opened = gate.accept(constantFrame(700))
        assertTrue(opened.shouldProcess)
        assertTrue(opened.opened)
        assertFalse(gate.accept(constantFrame(700)).opened)
    }

    @Test
    fun `a room that gets gradually louder raises the bar instead of opening the gate`() {
        val gate = WakeWordAudioGate()

        // Fan spinning up: each frame is louder but never 2.2x the tracked floor.
        var level = 200
        repeat(60) {
            assertFalse(gate.accept(constantFrame(level)).shouldProcess, "frame at $level opened the gate")
            level = (level * 1.05).toInt()
        }

        assertFalse(gate.accept(constantFrame(level + 200)).shouldProcess)
        assertTrue(gate.accept(constantFrame(level * 3)).shouldProcess)
    }

    private fun constantFrame(amplitude: Int): ShortArray =
        ShortArray(WakeWordModel.frameSamples) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
}
