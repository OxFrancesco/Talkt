package org.buddytools.talkt.data

import org.json.JSONObject

data class CloudTranscriptPage(val entries: List<TranscriptEntry>, val nextOffset: Int?)

object TranscriptCloudJson {
    fun encode(entry: TranscriptEntry): JSONObject = JSONObject()
        .put("id", entry.id).put("timeMillis", entry.timeMillis).put("text", entry.text)
        .put("kind", entry.kind).put("source", entry.source).put("reply", entry.reply ?: JSONObject.NULL)

    fun decode(json: JSONObject): TranscriptEntry = TranscriptEntry(
        json.getString("id"), json.getLong("timeMillis"), json.getString("text"),
        json.getString("kind"), json.getString("source"), if (json.isNull("reply")) null else json.getString("reply"),
    )
}
