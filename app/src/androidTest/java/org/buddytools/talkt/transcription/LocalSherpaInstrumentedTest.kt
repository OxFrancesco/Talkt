package org.buddytools.talkt.transcription

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.buddytools.talkt.transcription.local.SherpaServiceLocalDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSherpaInstrumentedTest {
    @Test
    fun transcribesEnglishAndItalianInThePrivateProcess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val decoder = SherpaServiceLocalDecoder(target)

        for (fixture in listOf("english.wav", "italian.wav")) {
            val wav = instrumentation.context.assets.open(fixture).use { it.readBytes() }
            val clip = RecordedClip.create(target.cacheDir, wav)
            try {
                val result = decoder.decode(clip)
                assertTrue("$fixture should decode locally, got $result", result is LocalAttempt.Decoded)
                result as LocalAttempt.Decoded
                assertTrue("$fixture returned blank text", result.text.isNotBlank())
                Log.i("TalktLocalTest", "$fixture [${result.language}]: ${result.text}")
            } finally {
                clip.close()
            }
        }
    }
}
