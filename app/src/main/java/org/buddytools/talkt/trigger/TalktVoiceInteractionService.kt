package org.buddytools.talkt.trigger

import android.service.voice.VoiceInteractionService
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.wakeword.TalktAssistantBridge
import org.buddytools.talkt.wakeword.WakeWordService

class TalktVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        TalktAssistantBridge.attach(this)
        if (TalktSettings(this).wakeWordEnabled) WakeWordService.start(this)
    }

    override fun onShutdown() {
        WakeWordService.stop(this)
        TalktAssistantBridge.attach(null)
        super.onShutdown()
    }
}
