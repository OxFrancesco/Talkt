package org.buddytools.talkt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AgentStatus(
    val hostHealthy: Boolean,
    val connected: Boolean,
    val label: String?,
)

data class AgentReply(
    val text: String,
    val provider: String,
    val fallback: Boolean,
)

class TalktApi(private val settings: TalktSettings) {
    suspend fun status(): AgentStatus = withContext(Dispatchers.IO) {
        val json = request("GET", "/v1/agent/status")
        AgentStatus(
            hostHealthy = json.getJSONObject("host").optBoolean("healthy"),
            connected = json.getJSONObject("connection").optBoolean("connected"),
            label = json.getJSONObject("connection").optString("label").ifBlank { null },
        )
    }

    suspend fun transcribe(wav: ByteArray, languageCodes: Set<String> = emptySet()): String = withContext(Dispatchers.IO) {
        val languageQuery = languageCodes
            .sorted()
            .joinToString(",")
            .takeIf(String::isNotEmpty)
            ?.let { "?languages=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" }
            .orEmpty()
        request("POST", "/v1/transcribe$languageQuery", wav, "audio/wav").getString("transcript")
    }

    suspend fun prompt(text: String): AgentReply = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).toString().toByteArray()
        val json = request("POST", "/v1/agent/prompt", body, "application/json")
        AgentReply(
            text = json.getString("text"),
            provider = json.getString("provider"),
            fallback = json.optBoolean("fallback"),
        )
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): JSONObject {
        val endpoint = settings.apiUrl
        val token = settings.appToken
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://127.0.0.1")) {
            throw TalktApiException("Set a valid HTTPS Talkt API URL")
        }
        if (token.isBlank()) throw TalktApiException("Set the Talkt app token")

        val connection = URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (connection.responseCode !in 200..299) {
                val message = json.optString("error", "Talkt API failed (${connection.responseCode})")
                throw TalktApiException(userFacingError(message))
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun userFacingError(message: String): String {
        val normalized = message.lowercase()
        return if ("provider.auth" in normalized) {
            "OpenRouter rejected the request. Check the API key connected to your Talkt agent."
        } else {
            message
        }
    }
}

class TalktApiException(message: String) : Exception(message)
