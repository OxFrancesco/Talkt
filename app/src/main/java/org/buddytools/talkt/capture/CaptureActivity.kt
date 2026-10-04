package org.buddytools.talkt.capture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.data.Snippet
import org.buddytools.talkt.data.SnippetStore
import org.buddytools.talkt.snippets.SnippetsActivity
import org.buddytools.talkt.trigger.EditorBridge
import org.buddytools.talkt.transcription.NoSpeechException
import org.buddytools.talkt.transcription.RecordedClip
import org.buddytools.talkt.transcription.Transcriber
import org.buddytools.talkt.transcription.TranscriptionGraph
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Ink
import org.buddytools.talkt.ui.Mint
import org.buddytools.talkt.ui.SurfaceRaised
import org.buddytools.talkt.ui.TalktTheme
import org.buddytools.talkt.wakeword.WakeWordService
import org.buddytools.thinkingorbs.ThinkingOrb
import org.buddytools.thinkingorbs.ThinkingOrbColors
import org.buddytools.thinkingorbs.ThinkingOrbParameters
import java.io.File

class CaptureActivity : ComponentActivity() {
    private val recorder by lazy { WavRecorder(RecordingStore(filesDir, accountId)) }
    private lateinit var settings: TalktSettings
    private lateinit var transcriber: Transcriber
    private var state by mutableStateOf<CaptureState>(CaptureState.Preparing)
    private var editorMode = false
    private lateinit var accountId: String
    private var source = ""
    private var claimedActive = false
    private var wakeDetectedAt = 0L
    private var microphoneHandoffMillis = 0L
    /** Recording currently being transcribed by this bubble; still on disk until archived. */
    private var transcribing: File? = null
    private var stopWatcher: Job? = null
    private var recordingSession = 0

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (state == CaptureState.Preparing) {
            if (granted) beginRecording() else state = CaptureState.Error("Microphone permission is required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = org.buddytools.talkt.auth.TalktAuth.userId
        if (owner == null) {
            startActivity(Intent(this, org.buddytools.talkt.MainActivity::class.java))
            finish()
            return
        }
        accountId = owner
        current?.get()?.finish()
        current = java.lang.ref.WeakReference(this)
        claimedActive = true
        settings = TalktSettings(this)
        transcriber = TranscriptionGraph.create(settings, accountId)
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        wakeDetectedAt = intent.getLongExtra(EXTRA_WAKE_DETECTED_AT, 0L)
        microphoneHandoffMillis = maxOf(
            intent.getLongExtra(EXTRA_MICROPHONE_HANDOFF_MILLIS, 0L),
            WakeWordService.pauseForCapture(),
        )
        // Never take window focus: the overlay only needs touches, and a focusable
        // window would steal focus from the editor beneath and hide its keyboard.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        editorMode = EditorBridge.captureTarget(preserveExisting = true) != null || EditorBridge.hasFreshTarget()
        Log.i(TAG, "capture opened source=$source editorMode=$editorMode accessibility=${EditorBridge.isConnected}")
        setContent {
            TalktTheme {
                CaptureScreen(
                    state = state,
                    onStop = ::finishRecording,
                    onClose = ::finish,
                    onEnableAccessibility = ::openAccessibilitySettings,
                    onListen = ::selectListening,
                    onSnippets = ::selectSnippets,
                    onUseSnippet = ::useSnippet,
                    onManageSnippets = {
                        startActivity(Intent(this, SnippetsActivity::class.java))
                        finish()
                    },
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, true)) ensureRecordingPermission()
    }

    override fun onDestroy() {
        if (claimedActive) {
            val wasCurrent = current?.get() === this
            if (wasCurrent) current = null
            if (state is CaptureState.Listening) keepInterruptedRecording()
            transcribing?.takeIf { it.exists() }?.let { file ->
                // lifecycleScope cancels the in-flight transcription; finish it in the background.
                Log.i(TAG, "capture closed while transcribing; handing ${file.name} to the background transcriber")
                PendingTranscriptionService.start(this, file, source, accountId)
            }
            if (source == "power") CaptureBus.finishPowerSession()
            if (wasCurrent) WakeWordService.resume(this)
        }
        super.onDestroy()
    }

    /** The bubble is going away mid-recording; hand whatever was heard to the background transcriber. */
    private fun keepInterruptedRecording() {
        val recording = recorder.stop()
        val kept = recording.file?.takeIf { recording.heardSpeech && !recording.silenced }
        Log.i(TAG, "capture interrupted while listening: duration=${recording.durationMillis}ms heardSpeech=${recording.heardSpeech} kept=${kept != null}")
        if (kept == null) {
            recording.file?.delete()
            return
        }
        PendingTranscriptionService.start(this, kept, source, accountId)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun ensureRecordingPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecording()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecording() {
        if (state != CaptureState.Preparing) return
        val session = ++recordingSession
        CaptureBus.consumeStopRequest()
        runCatching {
            org.buddytools.talkt.auth.TalktAuth.ensureUser(accountId)
            recorder.start(settings.silenceTimeoutSeconds * 1_000L) { reason ->
                Log.i(TAG, "recorder auto-stop: $reason")
                runOnUiThread { if (recordingSession == session) finishRecording() }
            }
        }
            .onSuccess {
                val detectionToMicMillis = wakeDetectedAt
                    .takeIf { it > 0L }
                    ?.let { SystemClock.elapsedRealtime() - it }
                Log.i(
                    TAG,
                    "capture microphone started source=$source " +
                        "wakeToMicMs=${detectionToMicMillis ?: -1} handoffMs=$microphoneHandoffMillis",
                )
                state = CaptureState.Listening
                stopWatcher?.cancel()
                stopWatcher = lifecycleScope.launch {
                    while (state is CaptureState.Listening) {
                        if (CaptureBus.consumeStopRequest()) {
                            finishRecording()
                            break
                        }
                        delay(40)
                    }
                }
            }
            .onFailure { state = CaptureState.Error(it.message ?: "Could not start microphone") }
    }

    private fun selectListening() {
        if (state !is CaptureState.Snippets && state !is CaptureState.Error) return
        state = CaptureState.Preparing
        ensureRecordingPermission()
    }

    private fun selectSnippets() {
        if (state !is CaptureState.Listening && state != CaptureState.Preparing && state !is CaptureState.Error) return
        state = CaptureState.Snippets(emptyList())
        ++recordingSession
        stopWatcher?.cancel()
        CaptureBus.consumeStopRequest()
        runCatching { recorder.discard() }
            .onSuccess { Log.i(TAG, "recording discarded for snippets: duration=${it.durationMillis}ms") }
            .onFailure { state = CaptureState.Error(it.message ?: "Could not discard the recording"); return }
        runCatching {
            org.buddytools.talkt.auth.TalktAuth.ensureUser(accountId)
            SnippetStore(this, accountId).list()
        }.onSuccess { state = CaptureState.Snippets(it) }
            .onFailure { state = CaptureState.Error(it.message ?: "Could not load snippets") }
    }

    private fun useSnippet(snippet: Snippet) {
        if (state !is CaptureState.Snippets) return
        runCatching {
            org.buddytools.talkt.auth.TalktAuth.ensureUser(accountId)
            copy(snippet.text, "Talkt snippet")
            val inserted = editorMode && EditorBridge.commit(snippet.text)
            val outcome = when {
                inserted -> TranscriptOutcome.Inserted
                EditorBridge.isConnected -> TranscriptOutcome.Copied
                else -> TranscriptOutcome.CopiedAccessibilityOff
            }
            Log.i(TAG, "snippet delivered: $outcome")
            state = CaptureState.Complete(snippet.text, outcome)
            lifecycleScope.launch {
                delay(if (outcome == TranscriptOutcome.CopiedAccessibilityOff) 4_000L else 1_100L)
                finish()
            }
        }.onFailure { state = CaptureState.Error(it.message ?: "Could not use snippet") }
    }

    private fun finishRecording() {
        if (state !is CaptureState.Listening) return
        val recording = recorder.stop()
        Log.i(
            TAG,
            "recording stopped: reason=${recording.reason} duration=${recording.durationMillis}ms " +
                "heardSpeech=${recording.heardSpeech} peakRms=${"%.0f".format(recording.peakRms)} " +
                "noiseFloor=${"%.0f".format(recording.noiseFloor)} speechRatio=${"%.2f".format(recording.speechRatio)} " +
                "silenced=${recording.silenced}",
        )
        if (recording.silenced && !recording.heardSpeech) {
            recording.file?.delete()
            state = CaptureState.Error("Another app is using the microphone")
            return
        }
        val file = recording.file
        if (file == null || recording.reason == StopReason.NoSpeech) {
            file?.delete()
            state = CaptureState.Error("I didn't hear anything")
            return
        }
        val clip = runCatching { RecordedClip.open(file) }
            .getOrElse {
                file.delete()
                state = CaptureState.Error(it.message ?: "Could not prepare the recording")
                return
            }
        state = CaptureState.Processing(label = "Transcribing")
        transcribing = file
        lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            runCatching { transcriber.transcribe(clip) }.onSuccess { transcript ->
                transcribing = null
                if (accountId != org.buddytools.talkt.auth.TalktAuth.userId) {
                    state = CaptureState.Error("Your Talkt account changed. Record again.")
                    return@onSuccess
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "transcribed via cloud in ${elapsed}ms (${transcript.text.length} chars, audio ${recording.durationMillis}ms)")
                val archived = runCatching { archive(transcript.text, kind = "transcript", source = "Cloud") }
                if (archived.isFailure) {
                    state = CaptureState.Error("Could not save the transcript. The recording is kept in History.")
                    return@onSuccess
                }
                if (accountId != org.buddytools.talkt.auth.TalktAuth.userId) return@onSuccess
                copy(transcript.text)
                val inserted = editorMode && EditorBridge.commit(transcript.text)
                val outcome = when {
                    inserted -> TranscriptOutcome.Inserted
                    EditorBridge.isConnected -> TranscriptOutcome.Copied
                    else -> TranscriptOutcome.CopiedAccessibilityOff
                }
                Log.i(TAG, "transcript delivered: $outcome")
                state = CaptureState.Complete(transcript.text, outcome)
                settings.latestResult = transcript.text
                // The transcript is archived; the audio has done its job.
                clip.close()
                delay(
                    when ((state as? CaptureState.Complete)?.outcome) {
                        TranscriptOutcome.CopiedAccessibilityOff -> 4_000L
                        else -> 1_100L
                    },
                )
                finish()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                transcribing = null
                Log.w(TAG, "transcription failed after ${SystemClock.elapsedRealtime() - startedAt}ms: ${error.message}")
                if (error is NoSpeechException) {
                    clip.close()
                    state = CaptureState.Error(error.message ?: "I didn't hear anything")
                } else {
                    // Keep the audio; History can retry it later.
                    Log.i(TAG, "recording kept for retry: ${file.name}")
                    state = CaptureState.Error("${error.message ?: "Talkt could not finish"}. The recording is saved in History.")
                }
            }
        }
    }

    private fun copy(text: String, label: String = "Talkt transcript") {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val existing = runCatching {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (existing == text) return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private suspend fun archive(text: String, kind: String, source: String, reply: String? = null) {
        withContext(Dispatchers.IO) {
            TranscriptStore(this@CaptureActivity, accountId).append(text, kind, source, reply)
        }
    }

    companion object {
        private const val TAG = "Talkt"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_AUTOSTART = "auto_start"
        private const val EXTRA_WAKE_DETECTED_AT = "wake_detected_at"
        private const val EXTRA_MICROPHONE_HANDOFF_MILLIS = "microphone_handoff_millis"
        private var current: java.lang.ref.WeakReference<CaptureActivity>? = null
        val isActive: Boolean get() = current?.get() != null

        fun intent(
            context: Context,
            source: String,
            autoStart: Boolean = true,
            wakeDetectedAt: Long = 0L,
            microphoneHandoffMillis: Long = 0L,
        ): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_AUTOSTART, autoStart)
                .putExtra(EXTRA_WAKE_DETECTED_AT, wakeDetectedAt)
                .putExtra(EXTRA_MICROPHONE_HANDOFF_MILLIS, microphoneHandoffMillis)
    }
}

internal enum class TranscriptOutcome { Inserted, Copied, CopiedAccessibilityOff }

internal sealed interface CaptureState {
    data object Preparing : CaptureState
    data object Listening : CaptureState
    data class Snippets(val entries: List<Snippet>) : CaptureState
    data class Processing(val label: String) : CaptureState
    data class Complete(val detail: String, val outcome: TranscriptOutcome) : CaptureState
    data class Error(val message: String) : CaptureState
}

@Composable
internal fun CaptureScreen(
    state: CaptureState,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onListen: () -> Unit,
    onSnippets: () -> Unit,
    onUseSnippet: (Snippet) -> Unit,
    onManageSnippets: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceRaised,
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state == CaptureState.Preparing || state == CaptureState.Listening || state is CaptureState.Snippets || state is CaptureState.Error) {
                    PrimaryTabRow(selectedTabIndex = if (state is CaptureState.Snippets) 1 else 0, containerColor = SurfaceRaised) {
                        Tab(selected = state !is CaptureState.Snippets, onClick = onListen,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Listening") })
                        Tab(selected = state is CaptureState.Snippets, onClick = onSnippets,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Snippets") })
                    }
                    Spacer(Modifier.height(12.dp))
                }
                when (state) {
                    CaptureState.Preparing -> ThinkingContent(label = "Getting ready")
                    CaptureState.Listening -> ListeningContent(onStop)
                    is CaptureState.Snippets -> SnippetsContent(state.entries, onUseSnippet, onManageSnippets, onClose)
                    is CaptureState.Processing -> ThinkingContent(label = state.label)
                    is CaptureState.Complete -> CompleteContent(state, onEnableAccessibility)
                    is CaptureState.Error -> ErrorContent(state.message, onClose)
                }
            }
        }
    }
}

@Composable
private fun SnippetsContent(entries: List<Snippet>, onUse: (Snippet) -> Unit, onManage: () -> Unit, onClose: () -> Unit) {
    if (entries.isEmpty()) {
        Text("No snippets yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            items(entries, key = { it.id }) { snippet ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Use ${snippet.title}") { onUse(snippet) }
                    .padding(vertical = 12.dp, horizontal = 4.dp)) {
                    Text(snippet.title, color = Cloud)
                    Spacer(Modifier.height(4.dp))
                    Text(snippet.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onManage) { Text(if (entries.isEmpty()) "Add snippet" else "Manage snippets") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun ThinkingContent(label: String) {
    val colors = MaterialTheme.colorScheme
    ThinkingOrb(
        size = 62.dp,
        parameters = ThinkingOrbParameters(
            speed = 0.72f,
            dots = 0.78f,
            spread = 0.92f,
            perspective = 0.9f,
        ),
        colors = ThinkingOrbColors(
            accent = colors.primary,
            dots = colors.onSurface,
            pill = colors.surfaceContainerHigh,
            label = colors.onSurface,
        ),
        contentDescription = label,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "$label…",
        color = colors.onSurface,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ListeningContent(onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Stop recording",
                role = Role.Button,
                onClick = onStop,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThinkingOrb(
            size = 68.dp,
            parameters = ThinkingOrbParameters(
                speed = 1.8f,
                spin = 1f,
                dots = 0.86f,
                spread = 0.96f,
                perspective = 0.92f,
            ),
            colors = ThinkingOrbColors(
                accent = colors.primary,
                dots = colors.onSurface,
                pill = colors.surfaceContainerHigh,
                label = colors.onSurface,
            ),
            contentDescription = "Listening",
        )
        Spacer(Modifier.height(6.dp))
        Text("Listening…", color = colors.onSurface, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        Text("Tap to stop", color = colors.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun CompleteContent(state: CaptureState.Complete, onEnableAccessibility: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = Ink)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        state.detail,
        color = Cloud,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Center,
        maxLines = 4,
    )
    Spacer(Modifier.height(6.dp))
    when (state.outcome) {
        TranscriptOutcome.Inserted -> Text("Inserted", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        TranscriptOutcome.Copied -> Text("Copied to clipboard", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        TranscriptOutcome.CopiedAccessibilityOff -> {
            Text(
                "Copied. Talkt's accessibility service is off, so the text was not typed into the field.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onEnableAccessibility) { Text("Turn on in Settings", color = Mint) }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onClose: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onClose) { Text("Close", color = Mint) }
}
