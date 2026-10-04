package org.buddytools.talkt.snippets

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.buddytools.talkt.capture.CaptureScreen
import org.buddytools.talkt.capture.CaptureState
import org.buddytools.talkt.capture.RecordingStore
import org.buddytools.talkt.capture.WavRecorder
import org.buddytools.talkt.data.SnippetStore
import org.buddytools.talkt.ui.TalktTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SnippetsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun createEditPersistAndDeleteSnippet() {
        val owner = "snippet-ui-${UUID.randomUUID()}"
        val store = SnippetStore(context, owner)
        compose.setContent { TalktTheme { SnippetsScreen(store, onDone = {}, checkAccount = {}) } }
        compose.onNodeWithText("Add snippet").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Meeting reply")
        compose.onNodeWithText("Text", substring = false).performTextInput("I can meet tomorrow at 10.")
        screenshot("01-create")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Meeting reply").assertIsDisplayed().performClick()
        compose.onNode(hasText("I can meet tomorrow at 10.") and hasSetTextAction()).performTextReplacement("I can meet tomorrow at 11.")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("I can meet tomorrow at 11.").assertIsDisplayed()
        assertEquals("I can meet tomorrow at 11.", SnippetStore(context, owner).list().single().text)
        assertTrue("Snippets must stay private to their account", SnippetStore(context, "$owner-other").list().isEmpty())
        screenshot("02-saved")
        compose.onNodeWithContentDescription("Delete Meeting reply").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Meeting reply").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete Meeting reply").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNodeWithText("No snippets yet").assertIsDisplayed()
        assertTrue(SnippetStore(context, owner).list().isEmpty())
    }

    @Test fun switchToSnippetsDiscardsAudioAndCanRestartListening() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val owner = "snippet-bubble-${UUID.randomUUID()}"
        val store = SnippetStore(context, owner)
        store.save("Meeting reply", "I can meet tomorrow at 11.")
        val recordings = RecordingStore(context.cacheDir, owner)
        val recorder = WavRecorder(recordings)
        val state = mutableStateOf<CaptureState>(CaptureState.Listening)
        var chosen = ""
        compose.mainClock.autoAdvance = false
        instrumentation.runOnMainSync { recorder.start(15_000L) {} }
        try {
            compose.setContent {
                TalktTheme {
                    CaptureScreen(state.value, onStop = {}, onClose = {}, onEnableAccessibility = {},
                        onListen = { recorder.start(15_000L) {}; state.value = CaptureState.Listening },
                        onSnippets = { recorder.discard(); state.value = CaptureState.Snippets(store.list()) },
                        onUseSnippet = { chosen = it.text }, onManageSnippets = {})
                }
            }
            assertTrue("An active recording must exist before switching", recordings.directory.listFiles().orEmpty().any { it.extension == "part" })
            screenshot("03-listening")
            compose.onNodeWithText("Snippets", substring = false).performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Meeting reply").assertIsDisplayed()
            assertTrue("Discarded audio must not remain for recovery", recordings.directory.listFiles().orEmpty().isEmpty())
            screenshot("04-bubble")
            compose.onNodeWithText("Meeting reply").performClick()
            compose.runOnIdle { assertEquals("I can meet tomorrow at 11.", chosen) }
            compose.onNodeWithText("Listening", substring = false).performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Tap to stop").assertIsDisplayed()
            assertTrue(recordings.directory.listFiles().orEmpty().any { it.extension == "part" })
            compose.onNodeWithText("Snippets", substring = false).performClick()
            compose.mainClock.advanceTimeBy(300)
            assertTrue(recordings.directory.listFiles().orEmpty().isEmpty())
        } finally {
            instrumentation.runOnMainSync { recorder.discard() }
            recordings.directory.deleteRecursively()
            store.list().forEach { store.remove(it.id) }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        instrumentation.waitForIdleSync()
        Thread.sleep(350) // The display compositor presents the frame after Compose becomes idle.
        val directory = requireNotNull(context.getExternalFilesDir("snippets-evidence")).apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
