package org.buddytools.talkt.transcription

import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings

object TranscriptionGraph {
    fun create(settings: TalktSettings, owner: String): Transcriber {
        val local = if (settings.transcriptionMode == "parakeet") ParakeetTranscriber(settings.appContext, settings) else null
        return Transcriber { clip ->
            TalktAuth.ensureUser(owner)
            val transcript = if (local != null) local.transcribe(clip) else {
                val text = TalktApi(settings).transcribe(clip.readWav(), settings.languageCodes, owner).trim()
                if (text.isEmpty()) throw NoSpeechException()
                Transcript(text)
            }
            TalktAuth.ensureUser(owner)
            transcript
        }
    }
}
