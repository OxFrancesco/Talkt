package org.buddytools.talkt.wakeword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantInvocationTest {
    @Test
    fun `missing source keeps the existing power-button behavior`() {
        val invocation = AssistantInvocation.fromWireValue(null)

        assertEquals(AssistantInvocation.Power, invocation)
        assertTrue(invocation.requiresPowerTrigger)
        assertEquals("power", invocation.captureSource)
    }

    @Test
    fun `hotword invocation does not depend on the power-button toggle`() {
        val invocation = AssistantInvocation.fromWireValue("hotword")

        assertEquals(AssistantInvocation.Hotword, invocation)
        assertFalse(invocation.requiresPowerTrigger)
        assertEquals("hotword", invocation.captureSource)
    }
}
