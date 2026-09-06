package org.buddytools.talkt.wakeword

enum class AssistantInvocation(
    val captureSource: String,
    val requiresPowerTrigger: Boolean,
) {
    Power(captureSource = "power", requiresPowerTrigger = true),
    Hotword(captureSource = "hotword", requiresPowerTrigger = false),
    ;

    companion object {
        const val ARG_SOURCE = "talkt_invocation_source"
        const val ARG_DETECTED_AT = "talkt_hotword_detected_at"

        fun fromWireValue(value: String?): AssistantInvocation =
            entries.firstOrNull { it.captureSource == value } ?: Power
    }
}
