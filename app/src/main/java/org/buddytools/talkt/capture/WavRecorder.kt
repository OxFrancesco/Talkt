package org.buddytools.talkt.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class StopReason { TrailingSilence, NoSpeech, MaxDuration, Manual }

class Recording(
    /** Finished WAV on disk, or null when nothing usable was captured. */
    val file: File?,
    val durationMillis: Long,
    val heardSpeech: Boolean,
    val peakRms: Double,
    val noiseFloor: Double,
    val speechRatio: Double,
    /** True if Android handed us zeros because another app owned the microphone. */
    val silenced: Boolean,
    val reason: StopReason,
) {
    val hasAudio: Boolean get() = file != null
}

/**
 * Records 16 kHz mono PCM straight into a `.part` file in the [RecordingStore]. The WAV header is
 * written up front with zero sizes and patched when the recording stops, so a process that dies
 * mid-recording still leaves recoverable audio behind.
 */
class WavRecorder(private val store: RecordingStore) {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var detector: VoiceActivityDetector? = null
    private var silenced = false
    private var autoStopReason: StopReason? = null
    private var part: File? = null
    private var output: FileOutputStream? = null

    @SuppressLint("MissingPermission")
    fun start(silenceTimeoutMillis: Long, onAutoStop: (StopReason) -> Unit) {
        if (!recording.compareAndSet(false, true)) return
        silenced = false
        autoStopReason = null
        val target = store.newRecordingFile().also { part = it }
        output = FileOutputStream(target).also { it.write(WavFormat.header(0)) }
        val voiceActivityDetector = VoiceActivityDetector(silenceTimeoutMillis).also { detector = it }
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
            if (Build.VERSION.SDK_INT >= 29) {
                recorder.registerAudioRecordingCallback(Handler(Looper.getMainLooper())::post, silenceWatcher)
            }
            recorder.startRecording()
        }
        worker = Thread(
            { readLoop(bufferSize, voiceActivityDetector, onAutoStop) },
            "talkt-recorder",
        ).also { it.start() }
    }

    fun stop(): Recording {
        val active = recording.compareAndSet(true, false)
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        if (Build.VERSION.SDK_INT >= 29) {
            audioRecord?.let { record ->
                if (record.activeRecordingConfiguration?.isClientSilenced == true) silenced = true
                runCatching { record.unregisterAudioRecordingCallback(silenceWatcher) }
            }
        }
        audioRecord?.release()
        audioRecord = null
        worker = null
        runCatching { output?.close() }
        output = null
        val vad = detector
        detector = null
        val file = part?.let { part ->
            this.part = null
            if (active && part.length() > WavFormat.HEADER_BYTES) {
                runCatching { store.finalize(part) }.getOrElse { part.delete(); null }
            } else {
                part.delete()
                null
            }
        }
        return Recording(
            file = file,
            durationMillis = vad?.processedMillis ?: 0L,
            heardSpeech = vad?.heardSpeech == true,
            peakRms = vad?.peakRms ?: 0.0,
            noiseFloor = vad?.noiseFloor ?: 0.0,
            speechRatio = vad?.speechRatio ?: 0.0,
            silenced = silenced,
            reason = autoStopReason ?: StopReason.Manual,
        )
    }

    private val silenceWatcher = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < 29) return
            val record = audioRecord ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
            if (mine.isClientSilenced) {
                silenced = true
                Log.w(TAG, "microphone silenced by the platform: another app owns the audio input")
            }
        }
    }

    private fun readLoop(
        bufferSize: Int,
        voiceActivityDetector: VoiceActivityDetector,
        onAutoStop: (StopReason) -> Unit,
    ) {
        val buffer = ByteArray(bufferSize)
        while (recording.get()) {
            val count = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (count > 0) {
                runCatching { output?.write(buffer, 0, count) }
                    .onFailure { Log.w(TAG, "recording write failed: ${it.message}") }
                if (voiceActivityDetector.acceptPcm16Le(buffer, count)) {
                    val reason = voiceActivityDetector.stopReason ?: StopReason.TrailingSilence
                    autoStopReason = reason
                    onAutoStop(reason)
                    break
                }
            }
        }
    }

    companion object {
        private const val TAG = "Talkt"
        private const val SAMPLE_RATE = WavFormat.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
