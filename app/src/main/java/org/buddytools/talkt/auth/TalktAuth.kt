package org.buddytools.talkt.auth

import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.buddytools.talkt.BuildConfig
import org.buddytools.talkt.data.TalktApiException

object TalktAuth {
    val userId: String? get() = if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) null else Clerk.user?.id

    suspend fun requireUserId(): String {
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) {
            throw TalktApiException("Talkt sign-in is not configured in this build")
        }
        if (withTimeoutOrNull(15_000) { Clerk.isInitialized.first { it } } == null) {
            throw TalktApiException("Could not load sign-in. Check your connection and open Talkt again.")
        }
        return userId ?: throw TalktApiException("Open Talkt and sign in to use cloud features")
    }

    fun ensureUser(expectedUserId: String) {
        if (userId != expectedUserId) throw TalktApiException("Your Talkt account changed. Try again.")
    }

    suspend fun token(expectedUserId: String): String {
        ensureUser(expectedUserId)
        val result = Clerk.auth.getToken()
        ensureUser(expectedUserId)
        return when (result) {
            is ClerkResult.Success -> result.value
            is ClerkResult.Failure -> throw TalktApiException("Could not refresh your session. Open Talkt and sign in again.")
        }
    }
}
