package org.buddytools.talkt.transcription

import org.buddytools.talkt.data.TalktApi

internal class GeminiRemoteDecoder(
    private val api: TalktApi,
    private val languageCodes: Set<String>,
) : RemoteDecoder {
    override suspend fun decode(clip: RecordedClip): Transcript =
        Transcript(
            text = api.transcribe(clip.readWav(), languageCodes).trim(),
            source = TranscriptSource.Gemini,
        )
}
