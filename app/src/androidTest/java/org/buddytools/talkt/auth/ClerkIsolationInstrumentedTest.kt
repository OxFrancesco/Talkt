package org.buddytools.talkt.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signup.sendCode
import com.clerk.api.user.delete
import com.clerk.api.signup.verifyCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.buddytools.talkt.BuildConfig
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.data.TranscriptStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClerkIsolationInstrumentedTest {
    @Test fun twoClerkAccountsHaveSeparateCloudAgentsAndArchives(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clerkE2E") == "true")
        assumeTrue(BuildConfig.CLERK_PUBLISHABLE_KEY.startsWith("pk_test_"))
        withTimeout(30_000) { Clerk.isInitialized.first { it } }
        assumeTrue("Do not replace an existing user session", Clerk.user == null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = TalktSettings(context)
        val previousUrl = settings.apiUrl
        InstrumentationRegistry.getArguments().getString("talktApiUrl")?.let { settings.apiUrl = it }
        val api = TalktApi(settings)
        val runId = java.util.UUID.randomUUID().toString()
        val aliceEmail = "talkt-alice+clerk_test_$runId@example.com"
        val testPassword = "Talkt-Test-$runId!"
        val alice = createUser(aliceEmail, testPassword)
        try {
            assertTrue(api.status().hostHealthy)
            assertFalse(api.status().connected)
            connectTestProvider(settings, "sk-or-v1-talkt-isolation-test-alice-$runId")
            assertTrue(api.status().connected)
            TranscriptStore(context).append("Alice private transcript", "agent", "test")
            requireSuccess(Clerk.auth.signOut())
            assertTrue(TranscriptStore(context).list().none { it.text == "Alice private transcript" })
            val bob = createUser("talkt-bob+clerk_test_$runId@example.com", testPassword)
            assertNotEquals(alice, bob)
            assertFalse("Bob must not inherit Alice's provider key", api.status().connected)
            assertTrue("Bob must not see Alice's local archive", TranscriptStore(context).list().isEmpty())
            assertTrue(TranscriptStore(context, alice).list().any { it.text == "Alice private transcript" })
            requireSuccess(Clerk.user!!.delete())
            requireSuccess(Clerk.auth.signOut())
            assertTrue(runCatching { TalktAuth.requireUserId() }.isFailure)
            android.util.Log.i("TalktClerkTest", "PASS target=${settings.apiUrl} distinctUsers=true aliceKeyConnected=true bobKeyConnected=false archivesIsolated=true signedOutBlocked=true")
        } finally {
            if (Clerk.user?.primaryEmailAddress?.emailAddress?.contains(runId) == true) {
                Clerk.user?.delete()
                Clerk.auth.signOut()
            }
            val signedIn = Clerk.auth.signInWithPassword { identifier = aliceEmail; password = testPassword }
            if (signedIn is ClerkResult.Success) {
                Clerk.user?.delete()
                Clerk.auth.signOut()
            }
            TranscriptStore(context, alice).clear()
            settings.apiUrl = InstrumentationRegistry.getArguments().getString("restoreApiUrl") ?: previousUrl
        }
    }

    private suspend fun connectTestProvider(settings: TalktSettings, key: String) = withContext(Dispatchers.IO) {
        val identity = TalktAuth.requireUserId()
        val token = TalktAuth.token(identity)
        val connection = URL(settings.apiUrl.trimEnd('/') + "/v1/agent/openrouter/key").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("key", key).toString().toByteArray()
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            assertEquals(200, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createUser(email: String, testPassword: String): String {
        val signUp = requireSuccess(Clerk.auth.signUp {
            this.email = email
            password = testPassword
        })
        requireSuccess(signUp.sendCode { this.email = email })
        requireSuccess(signUp.verifyCode("424242", VerificationType.EMAIL))
        return withTimeout(15_000) { Clerk.userFlow.first { it != null } }!!.id
    }

    private fun <T : Any, E : Any> requireSuccess(result: ClerkResult<T, E>): T = when (result) {
        is ClerkResult.Success -> result.value
        is ClerkResult.Failure -> error("Clerk test operation failed: ${result.error}")
    }
}
