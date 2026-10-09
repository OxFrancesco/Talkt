package org.buddytools.talkt.transcription

data class Transcript(val text: String, val source: String = "Cloud", val detail: String = "", val inferenceMillis: Double = 0.0)

fun interface Transcriber {
    suspend fun transcribe(clip: RecordedClip): Transcript
}

class NoSpeechException : Exception("I didn't hear anything")
