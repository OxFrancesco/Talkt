package org.buddytools.talkt.wakeword

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import java.lang.ref.WeakReference

internal object TalktAssistantBridge {
    private var service = WeakReference<VoiceInteractionService>(null)

    fun attach(value: VoiceInteractionService?) {
        service = WeakReference(value)
    }

    fun showHotwordSession(detectedAtElapsedRealtime: Long): Boolean {
        val assistant = service.get() ?: return false
        assistant.showSession(
            Bundle().apply {
                putString(AssistantInvocation.ARG_SOURCE, AssistantInvocation.Hotword.captureSource)
                putLong(AssistantInvocation.ARG_DETECTED_AT, detectedAtElapsedRealtime)
            },
            0,
        )
        return true
    }
}
