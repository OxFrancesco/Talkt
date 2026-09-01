package org.buddytools.talkt.cabinet

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        val store = TranscriptStore(this)
        setContent {
            TalktTheme {
                CabinetScreen(store = store, onClose = ::finish)
            }
        }
    }
}

@Composable
private fun CabinetScreen(store: TranscriptStore, onClose: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(store.list()) }
    var copiedID by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink).statusBarsPadding(),
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
                    TextButton(onClick = {
                        store.clear()
                        entries = emptyList()
                    }) {
                        Text("Clear all", color = Muted, fontSize = 13.sp)
                    }
                }
                TextButton(onClick = onClose) {
                    Text("Done", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No transcripts yet", color = Muted, fontSize = 14.sp)
                }
            }
        }

        items(entries, key = TranscriptEntry::id) { entry ->
            TranscriptCard(
                entry = entry,
                copied = copiedID == entry.id,
                onCopy = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Talkt transcript", entry.text))
                    copiedID = entry.id
                },
                onRemove = {
                    store.remove(entry.id)
                    entries = entries.filterNot { it.id == entry.id }
                },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TranscriptCard(entry: TranscriptEntry, copied: Boolean, onCopy: () -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceRaised)
            .clickable(onClick = onCopy)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(entry.timeMillis),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(kindLabel(entry), color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Text(entry.text, color = Cloud, fontSize = 15.sp, lineHeight = 21.sp)
        entry.reply?.let { reply ->
            Text(reply, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row {
            Text(
                if (copied) "Copied" else "Tap to copy",
                color = if (copied) Mint else Muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Remove",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onRemove),
            )
        }
    }
}

private fun kindLabel(entry: TranscriptEntry): String = when (entry.kind) {
    "agent" -> "Agent"
    "copy" -> "Copied"
    else -> "Inserted"
}

private fun formatTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMillis))
