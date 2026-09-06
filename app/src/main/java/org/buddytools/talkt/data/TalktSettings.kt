package org.buddytools.talkt.data

import android.content.Context
import org.buddytools.talkt.BuildConfig

enum class TranscriptionMode(val storedValue: String) {
    LocalFirst("local_first"),
    OnDeviceOnly("on_device_only"),
    GeminiOnly("gemini_only");

    companion object {
        fun fromStored(value: String?): TranscriptionMode =
            entries.firstOrNull { it.storedValue == value } ?: LocalFirst
    }
}

class TalktSettings(context: Context) {
    private val preferences = context.getSharedPreferences("talkt", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = preferences.getString(API_URL, BuildConfig.DEFAULT_API_URL) ?: BuildConfig.DEFAULT_API_URL
        set(value) = preferences.edit().putString(API_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = preferences.getString(APP_TOKEN, null)?.takeIf(String::isNotBlank) ?: BuildConfig.DEFAULT_APP_TOKEN
        set(value) = preferences.edit().putString(APP_TOKEN, value.trim()).apply()

    var latestResult: String
        get() = preferences.getString(LATEST_RESULT, "") ?: ""
        set(value) = preferences.edit().putString(LATEST_RESULT, value).apply()

    var silenceTimeoutSeconds: Int
        get() = preferences.getInt(SILENCE_TIMEOUT_SECONDS, DEFAULT_SILENCE_TIMEOUT_SECONDS)
            .coerceIn(MIN_SILENCE_TIMEOUT_SECONDS, MAX_SILENCE_TIMEOUT_SECONDS)
        set(value) = preferences.edit()
            .putInt(SILENCE_TIMEOUT_SECONDS, value.coerceIn(MIN_SILENCE_TIMEOUT_SECONDS, MAX_SILENCE_TIMEOUT_SECONDS))
            .apply()

    var volumeTriggerEnabled: Boolean
        get() = preferences.getBoolean(VOLUME_TRIGGER_ENABLED, true)
        set(value) = preferences.edit().putBoolean(VOLUME_TRIGGER_ENABLED, value).apply()

    var powerTriggerEnabled: Boolean
        get() = preferences.getBoolean(POWER_TRIGGER_ENABLED, true)
        set(value) = preferences.edit().putBoolean(POWER_TRIGGER_ENABLED, value).apply()

    var wakeWordEnabled: Boolean
        get() = preferences.getBoolean(WAKE_WORD_ENABLED, false)
        set(value) = preferences.edit().putBoolean(WAKE_WORD_ENABLED, value).apply()

    var agentEnabled: Boolean
        get() = preferences.getBoolean(AGENT_ENABLED, true)
        set(value) = preferences.edit().putBoolean(AGENT_ENABLED, value).apply()

    var transcriptionMode: TranscriptionMode
        get() = TranscriptionMode.fromStored(preferences.getString(TRANSCRIPTION_MODE, null))
        set(value) = preferences.edit().putString(TRANSCRIPTION_MODE, value.storedValue).apply()

    /** Empty means automatic language detection. */
    var languageCodes: Set<String>
        get() = preferences.getString(LANGUAGE_CODES, "")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        set(value) = preferences.edit()
            .putString(LANGUAGE_CODES, value.sorted().joinToString(","))
            .apply()

    companion object {
        const val DEFAULT_SILENCE_TIMEOUT_SECONDS = 3
        const val MIN_SILENCE_TIMEOUT_SECONDS = 1
        const val MAX_SILENCE_TIMEOUT_SECONDS = 10

        const val API_URL = "api_url"
        const val APP_TOKEN = "app_token"
        const val LATEST_RESULT = "latest_result"
        const val SILENCE_TIMEOUT_SECONDS = "silence_timeout_seconds"
        const val VOLUME_TRIGGER_ENABLED = "volume_trigger_enabled"
        const val POWER_TRIGGER_ENABLED = "power_trigger_enabled"
        const val WAKE_WORD_ENABLED = "wake_word_enabled"
        const val AGENT_ENABLED = "agent_enabled"
        const val TRANSCRIPTION_MODE = "transcription_mode"
        const val LANGUAGE_CODES = "language_codes"
    }
}
