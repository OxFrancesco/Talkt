package org.buddytools.talkt.trigger

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.KeyEvent
import org.buddytools.talkt.capture.CaptureActivity
import org.buddytools.talkt.capture.CaptureBus
import org.buddytools.talkt.data.TalktSettings

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
        if (!TalktSettings(context).powerTriggerEnabled) {
            finish()
            return
        }
        captureActive = true
        CaptureBus.bindPowerSession {
            captureActive = false
            finish()
        }
        EditorBridge.captureTarget(preserveExisting = true)
        val intent = CaptureActivity.intent(context, source = "power", autoStart = true).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        context.startActivity(intent)
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
