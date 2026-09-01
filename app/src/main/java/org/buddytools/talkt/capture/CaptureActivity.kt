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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else state = CaptureState.Error("Microphone permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TalktSettings(this)
        transcriber = TranscriptionGraph.create(this, settings)
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        editorMode = EditorBridge.hasFreshTarget()
        if (editorMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        setContent {
            TalktTheme {
                CaptureScreen(
                    state = state,
                    editorMode = editorMode,
                    source = source,
                    onStop = ::finishRecording,
                    onClose = ::finish,
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, true)) ensureRecordingPermission()
    }

    override fun onDestroy() {
        if (state is CaptureState.Listening) recorder.cancel()
        if (source == "power") CaptureBus.finishPowerSession()
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
                    if (editorMode) {
                        if (EditorBridge.commit(transcript.text)) {
                            archive(transcript.text, kind = "insert", source = source)
                            CaptureState.Complete("Inserted", transcript.text, false, source)
                        } else {
                            copy(transcript.text)
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
    editorMode: Boolean,
    source: String,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .padding(20.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceRaised,
            shape = RoundedCornerShape(30.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (editorMode) "DICTATION" else "AGENT",
                        color = Mint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(source.uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, null, tint = Muted)
                    }
                }
                Spacer(Modifier.height(20.dp))
                when (state) {
                    CaptureState.Preparing -> CircularProgressIndicator(color = Mint)
                    CaptureState.Listening -> ListeningContent(source, onStop)
                    is CaptureState.Processing -> ProcessingContent(state.label, state.engine)
                    is CaptureState.Complete -> CompleteContent(state)
                    is CaptureState.Error -> ErrorContent(state.message, onClose)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ListeningContent(source: String, onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice")
    Row(
        modifier = Modifier.height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
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
    Text("Listening", color = Cloud, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(
        when (source) {
            "power" -> "Release Power or tap stop"
            "volume" -> "Release Volume Down or tap stop"
            else -> "Pause for silence or tap stop"
        },
        color = Muted,
        fontSize = 14.sp,
    )
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(Icons.Rounded.GraphicEq, null)
        Spacer(Modifier.size(8.dp))
        Text("Stop", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProcessingContent(label: String, engine: String) {
    CircularProgressIndicator(color = Mint, strokeWidth = 3.dp)
    Spacer(Modifier.height(22.dp))
    Text(label, color = Cloud, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(engine, color = Muted, fontSize = 13.sp)
}

@Composable
private fun CompleteContent(state: CaptureState.Complete) {
    Box(Modifier.size(54.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = Ink)
    }
    Spacer(Modifier.height(18.dp))
    Text(state.title, color = Cloud, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(state.source, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
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
    Text("Couldn't finish", color = MaterialTheme.colorScheme.error, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(message, color = Muted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    Button(onClick = onClose) { Text("Close") }
}
