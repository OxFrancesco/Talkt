package org.buddytools.talkt.capture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class PendingRecording(
    val file: File,
    val recordedAt: Long,
    val durationMillis: Long,
)

/**
 * Every capture is written straight to app-private storage while the microphone is open, so a
 * bubble that gets killed mid-sentence or a transcription that fails leaves a playable WAV behind
 * instead of nothing. Files use the `.part` suffix while recording and become `.wav` once the
 * header is final; a `.part` file found at process start is an orphan and is adopted as-is.
 */
class RecordingStore(root: File) {
    val directory: File = File(root, DIRECTORY_NAME)

    fun newRecordingFile(now: Long = System.currentTimeMillis()): File {
        directory.mkdirs()
        return File(directory, "$now-${UUID.randomUUID()}$PART_SUFFIX")
    }

    /** Oldest first. */
    fun pending(): List<PendingRecording> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(WAV_SUFFIX) && it.length() > WavFormat.HEADER_BYTES }
        .map { PendingRecording(it, recordedAtOf(it), WavFormat.durationMillis(it)) }
        .sortedBy { it.recordedAt }

    /** Finalizes `.part` files left by a process that died while recording. Returns the adopted files. */
    fun adoptOrphans(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
        .mapNotNull { orphan ->
            if (orphan.length() <= WavFormat.HEADER_BYTES) {
                orphan.delete()
                null
            } else {
                runCatching { finalize(orphan) }.getOrNull()
            }
        }

    /** Patches the WAV header from the file length and renames `.part` to `.wav`. */
    fun finalize(part: File): File {
        WavFormat.repairHeader(part)
        val target = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX) + WAV_SUFFIX)
        if (!part.renameTo(target)) error("Could not keep the recording ${part.name}")
        return target
    }

    private fun recordedAtOf(file: File): Long =
        file.name.substringBefore('-').toLongOrNull() ?: file.lastModified()

    companion object {
        const val DIRECTORY_NAME = "recordings"
        const val PART_SUFFIX = ".part"
        const val WAV_SUFFIX = ".wav"
    }
}

/** 16 kHz mono PCM16 WAV, the only shape Talkt records. */
object WavFormat {
    const val HEADER_BYTES = 44
    const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    fun header(dataSize: Int): ByteArray {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(BYTES_PER_SECOND)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
    }

    /** Rewrites the size fields so they match the bytes on disk; a killed recorder leaves them at zero. */
    fun repairHeader(file: File) {
        val dataSize = (file.length() - HEADER_BYTES).coerceAtLeast(0L).toInt()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataSize))
        }
    }

    fun durationMillis(file: File): Long = (file.length() - HEADER_BYTES).coerceAtLeast(0L) * 1_000L / BYTES_PER_SECOND
}
