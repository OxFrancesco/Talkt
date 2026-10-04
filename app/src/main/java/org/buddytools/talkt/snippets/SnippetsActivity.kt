package org.buddytools.talkt.snippets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.data.Snippet
import org.buddytools.talkt.data.SnippetStore
import org.buddytools.talkt.ui.*

class SnippetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = TalktAuth.userId ?: run { finish(); return }
        val store = SnippetStore(this, owner)
        setContent {
            TalktTheme {
                val user by Clerk.userFlow.collectAsStateWithLifecycle()
                LaunchedEffect(user?.id) { if (user?.id != owner) finish() }
                SnippetsScreen(
                    store = store,
                    onDone = ::finish,
                    checkAccount = { TalktAuth.ensureUser(owner) },
                )
            }
        }
    }
}

@Composable
internal fun SnippetsScreen(store: SnippetStore, onDone: () -> Unit, checkAccount: () -> Unit) {
    var snippets by remember { mutableStateOf(emptyList<Snippet>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    fun refresh() {
        runCatching { checkAccount(); store.list() }
            .onSuccess { snippets = it }
            .onFailure { error = it.message ?: "Could not load snippets" }
    }
    LaunchedEffect(store) { refresh() }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Snippets", color = Cloud, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (snippets.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No snippets yet", color = Muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(snippets, key = { it.id }) { snippet ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(onClickLabel = "Edit ${snippet.title}") {
                            editing = snippet
                            showEditor = true
                        }.padding(vertical = 16.dp)) {
                            Text(snippet.title, color = Cloud, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(snippet.text, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { deleting = snippet }) {
                            Icon(Icons.Rounded.Delete, "Delete ${snippet.title}", tint = Muted)
                        }
                    }
                    HorizontalDivider(color = Hairline)
                }
            }
        }
        Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text("Add snippet")
        }
    }
    if (showEditor) {
        SnippetEditor(editing, onDismiss = { showEditor = false }) { title, text ->
            checkAccount()
            store.save(title, text, editing?.id)
            refresh()
            showEditor = false
        }
    }
    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${snippet.title}?") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { checkAccount(); store.remove(snippet.id) }
                        .onSuccess { refresh(); deleting = null }
                        .onFailure { error = it.message ?: "Could not delete snippet"; deleting = null }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SnippetEditor(snippet: Snippet?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(snippet) { mutableStateOf(snippet?.title.orEmpty()) }
    var text by remember(snippet) { mutableStateOf(snippet?.text.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (snippet == null) "Add snippet" else "Edit snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && text.isNotBlank(), onClick = {
                runCatching { onSave(title, text) }.onFailure { error = it.message ?: "Could not save snippet" }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
