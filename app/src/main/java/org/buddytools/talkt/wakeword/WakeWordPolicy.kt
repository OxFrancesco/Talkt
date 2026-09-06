package org.buddytools.talkt.wakeword

data class WakeWordPrerequisites(
    val enabled: Boolean = false,
    val microphoneGranted: Boolean = false,
    val activeAssistant: Boolean = false,
    val modelAvailable: Boolean = false,
)

enum class WakeWordAvailability {
    Disabled,
    NeedsMicrophone,
    NeedsAssistant,
    MissingModel,
    Ready,
}

object WakeWordPolicy {
    fun availability(prerequisites: WakeWordPrerequisites): WakeWordAvailability = when {
        !prerequisites.enabled -> WakeWordAvailability.Disabled
        !prerequisites.microphoneGranted -> WakeWordAvailability.NeedsMicrophone
        !prerequisites.activeAssistant -> WakeWordAvailability.NeedsAssistant
        !prerequisites.modelAvailable -> WakeWordAvailability.MissingModel
        else -> WakeWordAvailability.Ready
    }
}
