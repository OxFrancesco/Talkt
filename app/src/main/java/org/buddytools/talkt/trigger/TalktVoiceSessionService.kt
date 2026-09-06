package org.buddytools.talkt.trigger

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.KeyEvent
import org.buddytools.talkt.capture.CaptureActivity
import org.buddytools.talkt.capture.CaptureBus
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.wakeword.AssistantInvocation
import org.buddytools.talkt.wakeword.WakeWordService

class TalktVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TalktVoiceSession(this)
}

private class TalktVoiceSession(
    context: android.content.Context,
) : VoiceInteractionSession(context) {
    private var captureActive = false

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (captureActive) return
        val invocation = AssistantInvocation.fromWireValue(args?.getString(AssistantInvocation.ARG_SOURCE))
        if (invocation.requiresPowerTrigger && !TalktSettings(context).powerTriggerEnabled) {
            finish()
            return
        }
        captureActive = true
        if (invocation == AssistantInvocation.Power) {
            CaptureBus.bindPowerSession {
                captureActive = false
                finish()
            }
        }
        EditorBridge.captureTarget(preserveExisting = true)
        val microphoneHandoffMillis = WakeWordService.pauseForCapture()
        val intent = CaptureActivity.intent(
            context,
            source = invocation.captureSource,
            autoStart = true,
            wakeDetectedAt = args?.getLong(AssistantInvocation.ARG_DETECTED_AT, 0L) ?: 0L,
            microphoneHandoffMillis = microphoneHandoffMillis,
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        context.startActivity(intent)
        if (invocation == AssistantInvocation.Hotword) {
            captureActive = false
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (captureActive && keyCode in RELEASE_KEYS) true
        else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (captureActive && keyCode in RELEASE_KEYS) {
            CaptureBus.requestStop()
            true
        } else {
            super.onKeyUp(keyCode, event)
        }

    override fun onHide() {
        if (captureActive) CaptureBus.requestStop()
        super.onHide()
    }

    private companion object {
        val RELEASE_KEYS = setOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_ASSIST)
    }
}
