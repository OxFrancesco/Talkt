package org.buddytools.talkt.auth

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClerkCallbackInstrumentedTest {
    private val applicationId = "org.buddytools.talkt"
    private val base = "clerk://$applicationId.callback"

    @Test fun restoresTransferMarkerOnlyAfterServerVerification(): Unit = runBlocking {
        val result = resolveClerkCallback(Uri.parse(base), applicationId) { ClerkTransfer.SIGN_UP }!!
        assertEquals("failed", result.getQueryParameter("__clerk_status"))
        assertEquals("external_account_not_found", result.getQueryParameter("__clerk_error_code"))
        assertNull(result.getQueryParameter("rotating_token_nonce"))
    }

    @Test fun restoresExistingAccountTransfer(): Unit = runBlocking {
        val result = resolveClerkCallback(Uri.parse(base), applicationId) { ClerkTransfer.SIGN_IN }!!
        assertEquals("failed", result.getQueryParameter("__clerk_status"))
        assertEquals("external_account_exists", result.getQueryParameter("__clerk_error_code"))
        assertNull(result.getQueryParameter("rotating_token_nonce"))
    }

    @Test fun doesNotTurnUnverifiedCallbackIntoAuthentication(): Unit = runBlocking {
        assertEquals(base, resolveClerkCallback(Uri.parse(base), applicationId) { null }.toString())
    }

    @Test fun preservesSuccessfulCallbackWithoutFetchingTransferState(): Unit = runBlocking {
        val callback = Uri.parse("$base?rotating_token_nonce=test-nonce&created_session_id=sess_test")
        assertEquals(callback, resolveClerkCallback(callback, applicationId) { error("Unexpected fetch") })
    }

    @Test fun preservesExplicitFailureWithoutFetchingTransferState(): Unit = runBlocking {
        val callback = Uri.parse("$base?__clerk_status=failed&__clerk_error_code=access_denied")
        assertEquals(callback, resolveClerkCallback(callback, applicationId) { error("Unexpected fetch") })
    }

    @Test fun rejectsUnexpectedDestinations(): Unit = runBlocking {
        for (callback in listOf("https://$applicationId.callback", "clerk://other.callback", "$base/unexpected", "$base#unexpected")) {
            assertNull(resolveClerkCallback(Uri.parse(callback), applicationId) { error("Unexpected fetch") })
        }
    }
}
