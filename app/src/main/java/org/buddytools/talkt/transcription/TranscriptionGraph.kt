package org.buddytools.talkt.transcription

import android.content.Context
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptionMode
import org.buddytools.talkt.transcription.local.SherpaServiceLocalDecoder

object TranscriptionGraph {
    fun create(context: Context, settings: TalktSettings): Transcriber {
        val languages = settings.languageCodes
        val local = SherpaServiceLocalDecoder(
            context = context.applicationContext,
            languageHint = languages.singleOrNull().orEmpty(),
        )
        val remote = GeminiRemoteDecoder(TalktApi(settings), languages)
        return when (settings.transcriptionMode) {
            TranscriptionMode.LocalFirst -> LocalFirstTranscriber(local, remote)
            TranscriptionMode.OnDeviceOnly -> LocalOnlyTranscriber(local)
            TranscriptionMode.GeminiOnly -> RemoteOnlyTranscriber(remote)
        }
    }
}
