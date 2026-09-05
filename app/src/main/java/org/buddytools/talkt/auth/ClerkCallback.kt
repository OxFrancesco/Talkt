package org.buddytools.talkt.auth

import android.net.Uri
import com.clerk.api.network.model.verification.Verification

internal enum class ClerkTransfer(val errorCode: String) {
    SIGN_UP("external_account_not_found"),
    SIGN_IN("external_account_exists"),
}

internal fun verifiedClerkTransfer(verification: Verification?, direction: ClerkTransfer): ClerkTransfer? =
    direction.takeIf {
        verification?.status == Verification.Status.TRANSFERABLE &&
            verification.strategy?.startsWith("oauth_") == true &&
            verification.error?.code == direction.errorCode
    }

internal suspend fun resolveClerkCallback(
    uri: Uri,
    applicationId: String,
    verifiedTransfer: suspend () -> ClerkTransfer?,
): Uri? {
    if (uri.scheme != "clerk" || uri.host !in setOf("$applicationId.callback", "$applicationId.oauth") ||
        uri.port != -1 || uri.userInfo != null || uri.path !in listOf(null, "", "/") || uri.fragment != null) return null
    if (uri.query != null) return uri
    val transfer = verifiedTransfer() ?: return uri
    return uri.buildUpon()
        .appendQueryParameter("__clerk_status", "failed")
        .appendQueryParameter("__clerk_error_code", transfer.errorCode)
        .build()
}
