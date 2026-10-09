package org.buddytools.talkt

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.transcription.ParakeetModels
import org.buddytools.talkt.transcription.ParakeetTranscriber
import org.buddytools.talkt.transcription.RecordedClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ParakeetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun importAndTranscribeRealSpeechOnCpuAndGpu() = runBlocking {
        assumeTrue("Explicit device test with a staged QVAC model", InstrumentationRegistry.getArguments().getString("verifyParakeet") == "true")
        val staged = File(context.filesDir, "parakeet-test.gguf")
        assertTrue("Stage the official Q8 GGUF in the app's private files", staged.isFile)
        val settings = TalktSettings(context)
        val oldMode = settings.transcriptionMode
        val oldBackend = settings.parakeetBackend
        val oldModel = settings.parakeetModelFile
        val oldName = settings.parakeetModelName
        var imported: File? = null
        val wav = File(context.cacheDir, "parakeet-device-test.wav")
        try {
            ParakeetModels.import(context, settings, Uri.fromFile(staged)) {}
            imported = ParakeetModels.selected(context, settings)
            assertEquals(staged.length(), requireNotNull(imported).length())
            settings.transcriptionMode = "parakeet"
            settings.parakeetBackend = "cpu"
            instrumentation.context.assets.open("english.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
            val clip = RecordedClip.open(wav)
            val cpu = ParakeetTranscriber(context, settings).transcribe(clip)
            assertTrue("Real speech must produce words", cpu.text.split(' ').size >= 5)
            assertEquals("Parakeet CPU", cpu.source)
            settings.parakeetBackend = "opencl"
            val gpu = ParakeetTranscriber(context, settings).transcribe(clip)
            assertTrue("Explicit OpenCL must not silently run on CPU", gpu.source.contains("OpenCL"))
            assertTrue(gpu.detail.contains("GPU"))
            assertEquals("CPU/GPU transcript parity on the fixture", cpu.text, gpu.text)
            val warm = ParakeetTranscriber(context, settings).transcribe(clip)
            assertEquals(gpu.text, warm.text)
            assertEquals(gpu.source, warm.source)
            val evidence = requireNotNull(context.getExternalFilesDir("parakeet-evidence")).apply { mkdirs() }
            File(evidence, "native-parity.txt").writeText("CPU: ${cpu.detail}\nGPU cold: ${gpu.detail}\nGPU warm: ${warm.detail}\n${gpu.text}\n")
        } finally {
            settings.transcriptionMode = oldMode
            settings.parakeetBackend = oldBackend
            settings.parakeetModelFile = oldModel
            settings.parakeetModelName = oldName
            imported?.delete()
            wav.delete()
        }
    }
}
