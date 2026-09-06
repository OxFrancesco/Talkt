package org.buddytools.talkt.cabinet

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.buddytools.talkt.data.TranscriptEntry
import org.buddytools.talkt.data.TranscriptStore
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Ink
import org.buddytools.talkt.ui.Mint
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.SurfaceRaised
import org.buddytools.talkt.ui.TalktTheme
import java.text.DateFormat
import java.util.Date

class CabinetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TalktTheme {
                val user by com.clerk.api.Clerk.userFlow.collectAsStateWithLifecycle()
                androidx.compose.runtime.key(user?.id) {
                    CabinetScreen(store = remember(user?.id) { TranscriptStore(this, user?.id) })
                }
            }
        }
    }
}

@Composable
private fun CabinetScreen(store: TranscriptStore) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(store.list()) }
    var copiedID by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var informationEntry by remember { mutableStateOf<TranscriptEntry?>(null) }
    val visibleEntries = remember(entries, query) { fuzzySearchEntries(entries, query) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                    Text(
                        "Cabinet",
                        color = Cloud,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { deleteRequest = DeleteRequest.All }) {
                            Text("Clear all", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text("Search notes") },
                )
            }

            if (visibleEntries.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (entries.isEmpty()) "No notes yet" else "No matching notes",
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            items(visibleEntries, key = TranscriptEntry::id) { entry ->
                TranscriptCard(
                    entry = entry,
                    copied = copiedID == entry.id,
                    onCopy = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Talkt transcript", entry.text))
                        copiedID = entry.id
                    },
                    onRemove = { deleteRequest = DeleteRequest.Entry(entry) },
                    onInfo = { informationEntry = entry },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        deleteRequest?.let { request ->
            DeleteConfirmationDialog(
                request = request,
                onDismiss = { deleteRequest = null },
                onConfirm = {
                    when (request) {
                        DeleteRequest.All -> {
                            store.clear()
                            entries = emptyList()
                        }
                        is DeleteRequest.Entry -> {
                            store.remove(request.entry.id)
                            entries = entries.filterNot { it.id == request.entry.id }
                        }
                    }
                    deleteRequest = null
                },
            )
        }

        informationEntry?.let { entry ->
            NoteInformationDialog(
                entry = entry,
                onDismiss = { informationEntry = null },
            )
        }
    }
}

@Composable
private fun TranscriptCard(
    entry: TranscriptEntry,
    copied: Boolean,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onInfo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceRaised)
            .combinedClickable(
                onClick = onCopy,
                onLongClick = onInfo,
                onLongClickLabel = "Show note information",
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(entry.text, color = Cloud, fontSize = 15.sp, lineHeight = 21.sp)
        entry.reply?.let { reply ->
            Text(reply, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (copied) "Copied" else "Tap to copy",
                color = if (copied) Mint else Muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove) {
                Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoteInformationDialog(entry: TranscriptEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note information") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Created", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(formatTime(entry.timeMillis))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

private sealed interface DeleteRequest {
    data class Entry(val entry: TranscriptEntry) : DeleteRequest
    data object All : DeleteRequest
}

@Composable
private fun DeleteConfirmationDialog(
    request: DeleteRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deletingAll = request == DeleteRequest.All
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deletingAll) "Delete all notes?" else "Delete this note?") },
        text = {
            Text(
                if (deletingAll) {
                    "This permanently removes every note from this phone."
                } else {
                    "This note will be permanently removed from this phone."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMillis))
