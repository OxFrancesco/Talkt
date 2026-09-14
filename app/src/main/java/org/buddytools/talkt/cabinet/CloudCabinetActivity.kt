package org.buddytools.talkt.cabinet

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.buddytools.talkt.data.*
import org.buddytools.talkt.ui.*

class CloudCabinetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TalktTheme {
                val user by Clerk.userFlow.collectAsStateWithLifecycle()
                key(user?.id) { CloudCabinetScreen(user?.id, onBack = { finish() }) }
            }
        }
    }
}

@Composable
private fun CloudCabinetScreen(owner: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { TalktApi(TalktSettings(context)) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<TranscriptEntry>()) }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<TranscriptEntry?>(null) }
    var information by remember { mutableStateOf<TranscriptEntry?>(null) }

    LaunchedEffect(owner, query, revision) {
        entries = emptyList()
        nextOffset = null
        error = null
        if (owner == null) return@LaunchedEffect
        busy = true
        try {
            delay(300)
            val result = api.searchTranscripts(owner, query)
            entries = result.entries
            nextOffset = result.nextOffset
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't load cloud transcripts. Check your connection and sign-in." }
        finally { busy = false }
    }

    LazyColumn(Modifier.fillMaxSize().background(Ink).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Cloud transcripts", style = MaterialTheme.typography.titleLarge, color = Cloud, modifier = Modifier.weight(1f))
            TextButton(enabled = !busy && !deleting, onClick = { revision++ }) { Text("Refresh") }
        } }
        item { OutlinedTextField(value = query, onValueChange = { query = it.take(500) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search transcripts and replies") }) }
        if (owner == null) item { Text("Sign in to Talkt to search your cloud transcripts", color = Muted) }
        else if (busy && entries.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (error == null && entries.isEmpty()) item { Text(if (query.isBlank()) "No cloud transcripts yet" else "No matching transcripts", color = Muted) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        items(entries, key = TranscriptEntry::id) { entry ->
            TranscriptCard(entry, copied == entry.id, onCopy = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Talkt transcript", entry.text))
                copied = entry.id
            }, onRemove = { if (!deleting) removal = entry }, onInfo = { information = entry })
        }
        nextOffset?.let { offset -> item {
            TextButton(enabled = !busy && !deleting, onClick = {
                val expectedQuery = query
                val expectedRevision = revision
                scope.launch {
                    busy = true
                    try {
                        val result = api.searchTranscripts(requireNotNull(owner), expectedQuery, offset)
                        if (query == expectedQuery && revision == expectedRevision) {
                            entries = (entries + result.entries).distinctBy { it.id }
                            nextOffset = result.nextOffset
                            error = null
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (query == expectedQuery) error = "Couldn't load more transcripts. Try again." }
                    finally { if (query == expectedQuery && revision == expectedRevision) busy = false }
                }
            }) { Text(if (busy) "Loading..." else "Load more") }
        } }
    }
    removal?.let { entry -> AlertDialog(
        onDismissRequest = { removal = null }, title = { Text("Delete cloud transcript?") },
        text = { Text("Remove this transcript and its reply from your cloud archive and search results. Any copy on this phone stays available.") },
        dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            removal = null
            deleting = true
            scope.launch {
                try {
                    api.deleteCloudTranscript(requireNotNull(owner), entry.id)
                    revision++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn't delete the cloud transcript. Try again." }
                finally { deleting = false }
            }
        }) { Text("Delete") } },
    ) }
    information?.let { NoteInformationDialog(it, onDismiss = { information = null }) }
}
