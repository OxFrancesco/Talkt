package org.buddytools.talkt.transcription

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.Warning

@Composable
fun ParakeetSection(settings: TalktSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(settings.transcriptionMode) }
    var backend by remember { mutableStateOf(settings.parakeetBackend) }
    var modelName by remember { mutableStateOf(settings.parakeetModelName) }
    var ready by remember { mutableStateOf(ParakeetModels.selected(context, settings) != null) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val hexagon = remember { runCatching { ParakeetNative.hexagonAvailable() }.getOrDefault(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            progress = 0
            error = null
            try {
                ParakeetModels.import(context.applicationContext, settings, uri) { progress = it }
                modelName = settings.parakeetModelName
                ready = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Could not import the model"
            } finally {
                importing = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcription", color = Cloud, fontSize = 16.sp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cloud" to "Cloud", "parakeet" to "Parakeet on device").forEach { (value, label) ->
                FilterChip(selected = mode == value, onClick = { mode = value; settings.transcriptionMode = value },
                    label = { Text(label) }, enabled = !importing && (value == "cloud" || ready))
            }
        }
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
            Text(if (ready) "Replace GGUF model" else "Import Parakeet GGUF")
        }
        if (modelName.isNotBlank()) Text(modelName, color = Muted, fontSize = 12.sp)
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Importing ${progress / (1024 * 1024)} MB", color = Muted, fontSize = 12.sp)
        }
        if (mode == "parakeet") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cpu" to "CPU", "opencl" to "Adreno GPU", "hexagon" to "Hexagon NPU").forEach { (value, label) ->
                    FilterChip(selected = backend == value, onClick = { backend = value; settings.parakeetBackend = value },
                        label = { Text(label) }, enabled = !importing && (value != "hexagon" || hexagon))
                }
            }
            if (!hexagon) Text("Hexagon needs a build with the Qualcomm SDK. CPU and Adreno GPU are available.", color = Muted, fontSize = 12.sp)
            Text("Audio stays on this phone. Cloud backup is a separate setting. CTC models transcribe English; TDT v3 supports Italian and other languages.", color = Muted, fontSize = 12.sp)
        }
        error?.let { Text(it, color = Warning, fontSize = 12.sp) }
    }
}
