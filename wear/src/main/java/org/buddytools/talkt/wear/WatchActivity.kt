package org.buddytools.talkt.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WatchActivity : ComponentActivity() {
    private var toggleOnResume = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Toast.makeText(this, "Microphone permission is needed to record", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toggleOnResume = savedInstanceState == null && intent.getBooleanExtra(EXTRA_TOGGLE, false)
        setContent {
            val state by WatchStore.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.error) {
                state.error?.let {
                    Toast.makeText(this@WatchActivity, it, Toast.LENGTH_LONG).show()
                    WatchStore.state.value = WatchStore.state.value.copy(error = null)
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                WatchOrb(
                    recording = state.recording,
                    modifier = Modifier.size(176.dp).clickable(
                        role = Role.Button,
                        onClickLabel = if (state.recording) "Stop recording" else "Record audio",
                        onClick = ::toggleRecording,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        toggleOnResume = intent.getBooleanExtra(EXTRA_TOGGLE, false)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { WatchSyncWorker.requestPhone(this@WatchActivity) } }
        if (toggleOnResume) {
            toggleOnResume = false
            intent.removeExtra(EXTRA_TOGGLE)
            toggleRecording()
        }
    }

    private fun toggleRecording() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (WatchStore.state.value.recording) {
            startService(Intent(this, WatchRecordingService::class.java).setAction(WatchRecordingService.STOP))
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, WatchRecordingService::class.java)) }
            .onFailure { Toast.makeText(this, "Could not start recording. Try again.", Toast.LENGTH_SHORT).show() }
    }

    companion object { const val EXTRA_TOGGLE = "toggle_recording" }
}
