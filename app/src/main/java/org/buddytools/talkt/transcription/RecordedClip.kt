package org.buddytools.talkt.transcription

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.UUID

class RecordedClip private constructor(
    val id: String,
    internal val file: File,
    val durationMillis: Long,
) : Closeable {
    fun readWav(): ByteArray = file.readBytes()

    internal fun openReadOnly(): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun close() {
        file.delete()
    }

    companion object {
        private const val HEADER_BYTES = 44
        private const val BYTES_PER_SECOND = 16_000 * 2

        fun create(cacheDir: File, wav: ByteArray): RecordedClip {
            require(wav.size > HEADER_BYTES) { "The recording is empty" }
            require(wav.copyOfRange(0, 4).decodeToString() == "RIFF") { "Invalid WAV recording" }
            require(wav.copyOfRange(8, 12).decodeToString() == "WAVE") { "Invalid WAV recording" }

            val id = UUID.randomUUID().toString()
            val directory = File(cacheDir, "recordings").apply { mkdirs() }
            val file = File(directory, "$id.wav")
            file.outputStream().use { it.write(wav) }
            val duration = ((wav.size - HEADER_BYTES).toLong() * 1_000L) / BYTES_PER_SECOND
            return RecordedClip(id, file, duration)
        }
    }
}
