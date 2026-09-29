package org.buddytools.talkt.transcription

data class Transcript(val text: String)

fun interface Transcriber {
    suspend fun transcribe(clip: RecordedClip): Transcript
}

class NoSpeechException : Exception("I didn't hear anything")
