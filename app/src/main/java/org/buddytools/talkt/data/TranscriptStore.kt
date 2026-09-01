package org.buddytools.talkt.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class TranscriptEntry(
    val id: String,
    val timeMillis: Long,
    val text: String,
    val kind: String,
    val source: String,
    val reply: String?,
)

/** Append-only local archive of every transcript, stored as JSON lines in app-private storage. */
class TranscriptStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun append(text: String, kind: String, source: String, reply: String? = null) {
        if (text.isBlank()) return
        val entry = JSONObject()
            .put("id", java.util.UUID.randomUUID().toString())
            .put("time", System.currentTimeMillis())
            .put("text", text)
            .put("kind", kind)
            .put("source", source)
            .putOpt("reply", reply)
        synchronized(LOCK) {
            file.appendText(entry.toString() + "\n")
        }
    }

    /** Newest first. */
    fun list(): List<TranscriptEntry> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        file.readLines()
            .mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    TranscriptEntry(
                        id = json.getString("id"),
                        timeMillis = json.getLong("time"),
                        text = json.getString("text"),
                        kind = json.optString("kind", "insert"),
                        source = json.optString("source", ""),
                        reply = json.optString("reply").ifBlank { null },
                    )
                }.getOrNull()
            }
            .asReversed()
    }

    fun remove(id: String): Unit = synchronized(LOCK) {
        if (!file.exists()) return
        val kept = file.readLines().filterNot { line ->
            runCatching { JSONObject(line).getString("id") == id }.getOrDefault(false)
        }
        file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }

    fun clear(): Unit = synchronized(LOCK) {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "transcripts.jsonl"
        val LOCK = Any()
    }
}
