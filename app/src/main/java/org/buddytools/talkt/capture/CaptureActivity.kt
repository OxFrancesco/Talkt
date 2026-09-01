package org.buddytools.talkt.capture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.data.TranscriptionMode
import org.buddytools.talkt.trigger.EditorBridge
import org.buddytools.talkt.transcription.RecordedClip
import org.buddytools.talkt.transcription.Transcriber
import org.buddytools.talkt.transcription.TranscriptSource
import org.buddytools.talkt.transcription.TranscriptionGraph
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Ink
import org.buddytools.talkt.ui.Mint
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.SurfaceRaised
import org.buddytools.talkt.ui.TalktTheme

class CaptureActivity : ComponentActivity() {
    private val recorder = WavRecorder()
    private lateinit var settings: TalktSettings
    private lateinit var transcriber: Transcriber
    private var state by mutableStateOf<CaptureState>(CaptureState.Preparing)
    private var editorMode = false
    private var source = ""
    private var claimedActive = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else state = CaptureState.Error("Microphone permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current?.get()?.finish()
        current = java.lang.ref.WeakReference(this)
        claimedActive = true
        settings = TalktSettings(this)
        transcriber = TranscriptionGraph.create(this, settings)
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        // Never take window focus: the overlay only needs touches, and a focusable
        // window would steal focus from the editor beneath and hide its keyboard.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        editorMode = EditorBridge.captureTarget(preserveExisting = true) != null || EditorBridge.hasFreshTarget()
        setContent {
            TalktTheme {
                CaptureScreen(
                    state = state,
                    onStop = ::finishRecording,
                    onClose = ::finish,
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, true)) ensureRecordingPermission()
    }

    override fun onDestroy() {
        if (claimedActive) {
            if (current?.get() === this) current = null
            if (state is CaptureState.Listening) recorder.cancel()
            if (source == "power") CaptureBus.finishPowerSession()
        }
        super.onDestroy()
    }

    private fun ensureRecordingPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecording()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecording() {
        runCatching {
            recorder.start(settings.silenceTimeoutSeconds * 1_000L) {
                runOnUiThread(::finishRecording)
            }
        }
            .onSuccess {
                state = CaptureState.Listening
                lifecycleScope.launch {
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

    private fun finishRecording() {
        if (state !is CaptureState.Listening) return
        val wav = recorder.stop()
        if (wav.size <= 44) {
            state = CaptureState.Error("I didn't hear anything")
            return
        }
        val clip = runCatching { RecordedClip.create(cacheDir, wav) }
            .getOrElse {
                state = CaptureState.Error(it.message ?: "Could not prepare the recording")
                return
            }
        state = CaptureState.Processing(
            label = if (editorMode) "Fixing your text" else "Starting your agent",
            engine = when (settings.transcriptionMode) {
                TranscriptionMode.LocalFirst -> "On-device first · Gemini fallback"
                TranscriptionMode.OnDeviceOnly -> "On-device only"
                TranscriptionMode.GeminiOnly -> "Gemini"
            },
        )
        lifecycleScope.launch {
            runCatching {
                try {
                    val transcript = transcriber.transcribe(clip)
                    val source = when (transcript.source) {
                        TranscriptSource.OnDevice -> "Transcribed on device"
                        TranscriptSource.Gemini -> "Gemini fallback"
                    }
                    copy(transcript.text)
                    if (editorMode) {
                        if (EditorBridge.commit(transcript.text)) {
                            archive(transcript.text, kind = "insert", source = source)
                            CaptureState.Complete("Inserted · copied", transcript.text, false, source)
                        } else {
                            archive(transcript.text, kind = "copy", source = source)
                            CaptureState.Complete("Copied for pasting", transcript.text, false, source)
                        }
                    } else {
                        val reply = TalktApi(settings).prompt(transcript.text)
                        archive(transcript.text, kind = "agent", source = source, reply = reply.text)
                        CaptureState.Complete(
                            title = if (reply.fallback) "Claude answered" else "GPT answered",
                            detail = reply.text,
                            isAgent = true,
                            source = source,
                        )
                    }
                } finally {
                    clip.close()
                }
            }.onSuccess { result ->
                state = result
                settings.latestResult = result.detail
                if (editorMode) {
                    delay(1_100)
                    finish()
                }
            }.onFailure { error ->
                state = CaptureState.Error(error.message ?: "Talkt could not finish")
            }
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val existing = runCatching {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (existing == text) return
        clipboard.setPrimaryClip(ClipData.newPlainText("Talkt transcript", text))
    }

    private suspend fun archive(text: String, kind: String, source: String, reply: String? = null) {
        withContext(Dispatchers.IO) {
            runCatching { TranscriptStore(this@CaptureActivity).append(text, kind, source, reply) }
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_AUTOSTART = "auto_start"
        private var current: java.lang.ref.WeakReference<CaptureActivity>? = null

        fun intent(context: Context, source: String, autoStart: Boolean = true): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_AUTOSTART, autoStart)
    }
}

private sealed interface CaptureState {
    data object Preparing : CaptureState
    data object Listening : CaptureState
    data class Processing(val label: String, val engine: String) : CaptureState
    data class Complete(
        val title: String,
        val detail: String,
        val isAgent: Boolean,
        val source: String,
    ) : CaptureState
    data class Error(val message: String) : CaptureState
}

@Composable
private fun CaptureScreen(
    state: CaptureState,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
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
                when (state) {
                    CaptureState.Preparing -> CircularProgressIndicator(color = Mint, strokeWidth = 3.dp)
                    CaptureState.Listening -> ListeningContent(onStop)
                    is CaptureState.Processing -> CircularProgressIndicator(color = Mint, strokeWidth = 3.dp)
                    is CaptureState.Complete -> CompleteContent(state)
                    is CaptureState.Error -> ErrorContent(state.message, onClose)
                }
            }
        }
    }
}

@Composable
private fun ListeningContent(onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onStop),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(9) { index ->
            val height by transition.animateFloat(
                initialValue = 12f + (index % 3) * 5,
                targetValue = 34f + ((index * 7) % 4) * 6,
                animationSpec = infiniteRepeatable(
                    animation = tween(430 + index * 35),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                Modifier
                    .size(width = 5.dp, height = height.dp)
                    .clip(CircleShape)
                    .background(Mint),
            )
        }
    }
}

@Composable
private fun CompleteContent(state: CaptureState.Complete) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = Ink)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        state.detail,
        color = if (state.isAgent) Cloud else Muted,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Center,
        maxLines = if (state.isAgent) 8 else 4,
    )
}

@Composable
private fun ErrorContent(message: String, onClose: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onClose) { Text("Close", color = Mint) }
}
