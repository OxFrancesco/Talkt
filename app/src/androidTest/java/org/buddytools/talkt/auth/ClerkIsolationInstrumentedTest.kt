package org.buddytools.talkt.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.verifyCode
import com.clerk.api.signup.sendCode
import com.clerk.api.user.delete
import com.clerk.api.signup.verifyCode
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
    @Test fun twoClerkAccountsCanTranscribeWithSeparateArchives(): Unit = runBlocking {
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
        var cloudEntryId: String? = null
        try {
            val audio = InstrumentationRegistry.getInstrumentation().context.assets.open("cloud-test.wav").use { it.readBytes() }
            val transcript = api.transcribe(audio, setOf("en"), alice)
            assertTrue("Cloud transcription returns text", transcript.isNotBlank())
            val entry = org.buddytools.talkt.data.TranscriptEntry(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), "Alice private transcript $runId", "transcript", "test", null)
            cloudEntryId = entry.id
            assertTrue(api.saveTranscript(alice, entry))
            TranscriptStore(context).append(entry.text, "transcript", "test")
            requireSuccess(Clerk.auth.signOut())
            assertTrue(TranscriptStore(context).list().none { it.text.startsWith("Alice private transcript") })
            val bob = createUser("talkt-bob+clerk_test_$runId@example.com", testPassword)
            assertNotEquals(alice, bob)
            assertTrue("Bob can transcribe without configuring a provider", api.transcribe(audio, setOf("en"), bob).isNotBlank())
            assertTrue("Bob cannot search Alice's cloud archive", api.searchTranscripts(bob, runId).entries.isEmpty())
            assertTrue("Bob cannot read Alice's cloud transcript", runCatching { api.cloudTranscript(bob, entry.id) }.isFailure)
            assertTrue("Bob must not see Alice's local archive", TranscriptStore(context).list().isEmpty())
            assertTrue(TranscriptStore(context, alice).list().any { it.text.startsWith("Alice private transcript") })
            requireSuccess(Clerk.user!!.delete())
            requireSuccess(Clerk.auth.signOut())
            assertTrue(runCatching { TalktAuth.requireUserId() }.isFailure)
            android.util.Log.i("TalktClerkTest", "PASS target=${settings.apiUrl} distinctUsers=true cloudTranscriptionBothAccounts=true archivesIsolated=true signedOutBlocked=true")
        } finally {
            if (Clerk.user?.primaryEmailAddress?.emailAddress?.contains(runId) == true) {
                Clerk.user?.delete()
                Clerk.auth.signOut()
            }
            val signIn = requireSuccess(Clerk.auth.signInWithOtp { email = aliceEmail })
            requireSuccess(signIn.verifyCode("424242"))
            withTimeout(15_000) { Clerk.userFlow.first { it?.id == alice } }
            cloudEntryId?.let { api.deleteCloudTranscript(alice, it) }
            requireSuccess(Clerk.user!!.delete())
            Clerk.auth.signOut()
            TranscriptStore(context, alice).clear()
            settings.apiUrl = InstrumentationRegistry.getArguments().getString("restoreApiUrl") ?: previousUrl
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
