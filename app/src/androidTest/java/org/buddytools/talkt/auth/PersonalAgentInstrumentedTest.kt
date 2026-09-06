package org.buddytools.talkt.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clerk.api.Clerk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.buddytools.talkt.data.TalktSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class PersonalAgentInstrumentedTest {
    @Test fun ownerAppSeesTelegramConversation(): Unit = runBlocking {
        withTimeout(30_000) { Clerk.isInitialized.first { it } }
        assertEquals("oddofrancesco000@gmail.com", Clerk.user?.primaryEmailAddress?.emailAddress)
        val identity = TalktAuth.requireUserId()
        val token = TalktAuth.token(identity)
        val settings = TalktSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        fun get(path: String): String {
            val connection = URL(settings.apiUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                assertEquals(200, connection.responseCode)
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }
        val capabilities = JSONObject(get("/v1/agent/capabilities"))
        assertTrue(capabilities.getBoolean("personal"))
        assertTrue(capabilities.getJSONObject("profile").getInt("skills") > 0)
        val session = capabilities.getString("conversationID")
        val history = get("/v1/conversations/context?sessionID=$session")
        assertTrue("The app must see the real Telegram exchange", history.contains("TALKT_TELEGRAM_OK"))
        android.util.Log.i("TalktPersonalTest", "PASS owner=true skills=${capabilities.getJSONObject("profile").getInt("skills")} sharedSession=$session telegramHistory=true")
    }
}
