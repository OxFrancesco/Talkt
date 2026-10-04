package org.buddytools.talkt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Snippet(val id: String, val title: String, val text: String)

class SnippetStore(context: Context, userId: String) {
    private val preferences = context.getSharedPreferences("snippets-${accountStorageSuffix(userId)}", Context.MODE_PRIVATE)

    fun list(): List<Snippet> = synchronized(LOCK) {
        val entries = JSONArray(preferences.getString("entries", "[]"))
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            Snippet(entry.getString("id"), entry.getString("title"), entry.getString("text"))
        }
    }

    fun save(title: String, text: String, id: String? = null): Unit = synchronized(LOCK) {
        require(title.isNotBlank() && text.isNotBlank()) { "Enter a name and text" }
        val snippet = Snippet(id ?: UUID.randomUUID().toString(), title.trim(), text)
        val entries = list().toMutableList()
        val index = entries.indexOfFirst { it.id == snippet.id }
        if (index < 0) entries.add(snippet) else entries[index] = snippet
        write(entries)
    }

    fun remove(id: String): Unit = synchronized(LOCK) {
        write(list().filterNot { it.id == id })
    }

    private fun write(entries: List<Snippet>) {
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)) }
        check(preferences.edit().putString("entries", json.toString()).commit()) { "Could not save snippets" }
    }

    private companion object { val LOCK = Any() }
}
