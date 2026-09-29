package org.buddytools.talkt.transcription

import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings

object TranscriptionGraph {
    fun create(settings: TalktSettings, owner: String): Transcriber = Transcriber { clip ->
        TalktAuth.ensureUser(owner)
        val text = TalktApi(settings).transcribe(clip.readWav(), settings.languageCodes, owner).trim()
        TalktAuth.ensureUser(owner)
        if (text.isEmpty()) throw NoSpeechException()
        Transcript(text)
    }
}
