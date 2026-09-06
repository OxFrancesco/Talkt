package org.buddytools.talkt.wakeword

import kotlin.test.Test
import kotlin.test.assertEquals

class WakeWordPolicyTest {
    @Test
    fun `ready only when every user-facing prerequisite is satisfied`() {
        assertEquals(
            WakeWordAvailability.Ready,
            WakeWordPolicy.availability(
                WakeWordPrerequisites(
                    enabled = true,
                    microphoneGranted = true,
                    activeAssistant = true,
                    modelAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun `disabled wins over missing setup requirements`() {
        assertEquals(
            WakeWordAvailability.Disabled,
            WakeWordPolicy.availability(WakeWordPrerequisites()),
        )
    }

    @Test
    fun `setup failures identify the next action`() {
        assertEquals(
            WakeWordAvailability.NeedsMicrophone,
            WakeWordPolicy.availability(
                WakeWordPrerequisites(enabled = true),
            ),
        )
        assertEquals(
            WakeWordAvailability.NeedsAssistant,
            WakeWordPolicy.availability(
                WakeWordPrerequisites(enabled = true, microphoneGranted = true),
            ),
        )
        assertEquals(
            WakeWordAvailability.MissingModel,
            WakeWordPolicy.availability(
                WakeWordPrerequisites(
                    enabled = true,
                    microphoneGranted = true,
                    activeAssistant = true,
                ),
            ),
        )
    }
}
