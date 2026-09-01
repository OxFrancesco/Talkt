package org.buddytools.talkt.trigger

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class TalktRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback) = Unit

    override fun onCancel(callback: Callback) = Unit
}
