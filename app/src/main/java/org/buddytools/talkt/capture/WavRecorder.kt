package org.buddytools.talkt.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorder {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(silenceTimeoutMillis: Long, onSilence: () -> Unit) {
        if (!recording.compareAndSet(false, true)) return
        pcm.reset()
        val voiceActivityDetector = VoiceActivityDetector(silenceTimeoutMillis)
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 2)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        ).also { recorder ->
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            recorder.startRecording()
        }
        worker = Thread(
            { readLoop(bufferSize, voiceActivityDetector, onSilence) },
            "talkt-recorder",
        ).also { it.start() }
    }

    fun stop(): ByteArray {
        if (!recording.compareAndSet(true, false)) return ByteArray(0)
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        worker = null
        return wav(pcm.toByteArray())
    }

    fun cancel() {
        stop()
        pcm.reset()
    }

    private fun readLoop(
        bufferSize: Int,
        voiceActivityDetector: VoiceActivityDetector,
        onSilence: () -> Unit,
    ) {
        val buffer = ByteArray(bufferSize)
        while (recording.get()) {
            val count = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (count > 0) {
                pcm.write(buffer, 0, count)
                if (voiceActivityDetector.acceptPcm16Le(buffer, count)) {
                    onSilence()
                    break
                }
            }
        }
    }

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        return header.array() + data
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
