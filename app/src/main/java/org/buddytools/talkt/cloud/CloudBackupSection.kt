package org.buddytools.talkt.cloud

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.cabinet.CloudCabinetActivity

@Composable
fun CloudBackupSection() {
    val user by Clerk.userFlow.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        key(user?.id) { CloudBackupControls(user?.id) }
    }
}

@Composable
private fun CloudBackupControls(owner: String?) {
    val context = LocalContext.current
    val state = remember(owner) { owner?.let { CloudBackupState(context, it) } }
    var revision by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf(false) }
    var includeExisting by remember { mutableStateOf(false) }
    DisposableEffect(state) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        state?.preferences?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { state?.preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = remember(revision, state) { state?.enabled == true }
    val status = remember(revision, state) { state?.preferences?.getString("status", null) }
    LaunchedEffect(owner) { CloudBackupWorker.enqueue(context, owner) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, enabled = owner != null, onCheckedChange = {
                if (it) dialog = true else owner?.let { id -> CloudBackupWorker.disable(context, id) }
            })
        }
        if (owner == null) Text("Sign in to back up and search transcripts", style = MaterialTheme.typography.bodyMedium)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (owner != null) Row {
            if (enabled) TextButton(onClick = { CloudBackupWorker.enqueue(context, owner) }) { Text("Back up now") }
            TextButton(onClick = { context.startActivity(Intent(context, CloudCabinetActivity::class.java)) }) { Text("Search cloud transcripts") }
        }
    }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("Back up transcripts?") },
        text = { Column {
            Text("Save new transcripts and agent replies to your Talkt account. Local copies stay available offline. Deleting a local note keeps its cloud copy.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeExisting, onCheckedChange = { includeExisting = it })
                Text("Include existing transcripts")
            }
        } },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            if (owner != null && org.buddytools.talkt.auth.TalktAuth.userId == owner) {
                state?.enable(if (includeExisting) emptySet() else TranscriptStore(context, owner).list().map { it.id }.toSet())
                CloudBackupWorker.enqueue(context, owner)
            }
            dialog = false
        }) { Text("Enable backup") } },
    )
}
