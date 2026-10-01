package org.buddytools.talkt.wear

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.buddytools.talkt.wearbridge.WearProtocol
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class WatchState(
    val recording: Boolean = false,
    val error: String? = null,
)

class WatchStore(context: Context) {
    val directory = File(context.filesDir, "outbox").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    val owner: String get() = preferences.getString("owner", "").orEmpty()
    val phone: String get() = preferences.getString("phone", "").orEmpty()
    private val recordingOwner: String get() = owner.ifBlank { preferences.getString("lastOwner", "").orEmpty() }

    fun account(node: String, token: String) {
        val edit = preferences.edit().putString("phone", node).putString("owner", token)
        if (token.isNotBlank()) edit.putString("lastOwner", token)
        check(edit.commit())
    }

    fun newPart(): File {
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        writeBinding(id, recordingOwner, phone)
        return File(directory, "$id.part")
    }

    fun binding(id: String): JSONObject = JSONObject(AtomicFile(File(directory, "$id.json")).readFully().decodeToString())

    fun bindUnassigned() {
        if (owner.isBlank() || phone.isBlank()) return
        pending().filter { binding(it.nameWithoutExtension).optString("owner").isBlank() }.forEach {
            writeBinding(it.nameWithoutExtension, owner, phone)
        }
    }

    private fun writeBinding(id: String, token: String, node: String) {
        val atomic = AtomicFile(File(directory, "$id.json"))
        val output = atomic.startWrite()
        try {
            output.write(JSONObject().put("owner", token).put("phone", node).toString().toByteArray())
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun pending(): List<File> = directory.listFiles().orEmpty()
        .filter { it.extension == "wav" }.sortedBy { it.name }

    fun finish(part: File): File? {
        if (part.length() <= 44) {
            part.delete()
            File(directory, "${part.nameWithoutExtension}.json").delete()
            return null
        }
        RandomAccessFile(part, "rw").use {
            val length = minOf(it.length(), WearProtocol.MAX_AUDIO_BYTES.toLong())
            it.setLength(length - length % 2)
            it.seek(0)
            it.write(WearProtocol.wavHeader((it.length() - 44).toInt()))
            it.fd.sync()
        }
        val wav = File(directory, "${part.nameWithoutExtension}.wav")
        check(part.renameTo(wav)) { "Could not save recording" }
        return wav
    }

    fun recover() {
        directory.listFiles().orEmpty().filter { it.extension == "part" }.forEach { file ->
            runCatching { finish(file) }.onFailure {
                android.util.Log.w("Talkt", "Watch audio recovery deferred: ${file.name}", it)
            }
        }
    }

    companion object { val state = MutableStateFlow(WatchState()) }
}
