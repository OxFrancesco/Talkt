package org.buddytools.talkt.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.get
import com.clerk.api.signup.get
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ClerkCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        lifecycleScope.launch {
            val callback = resolveClerkCallback(uri, packageName) {
                withTimeoutOrNull(15_000) {
                    Clerk.isInitialized.first { it }
                    val signUp = Clerk.auth.currentSignUp
                    val signIn = Clerk.auth.currentSignIn
                    if (signUp != null) {
                        val result = signUp.get()
                        val verification = (result as? ClerkResult.Success)?.value?.verifications?.get("external_account")
                        verifiedClerkTransfer(verification, ClerkTransfer.SIGN_IN)
                    } else if (signIn != null) {
                        val result = signIn.get()
                        val verification = (result as? ClerkResult.Success)?.value?.firstFactorVerification
                        verifiedClerkTransfer(verification, ClerkTransfer.SIGN_UP)
                    } else null
                }
            }
            if (callback != null) {
                startActivity(Intent(Intent.ACTION_VIEW, callback)
                    .setClassName(packageName, "com.clerk.api.sso.SSOReceiverActivity"))
            }
            finish()
        }
    }
}
