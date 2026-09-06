package org.buddytools.talkt.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signup.SignUp
import com.clerk.api.sso.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.buddytools.talkt.ui.Ink
import org.buddytools.talkt.ui.Warning

@Composable
fun GoogleSignInButton(onClose: () -> Unit) {
    val initialized by Clerk.isInitialized.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = initialized && !busy,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White, contentColor = Ink),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        when (val result = SignUp.authenticateWithRedirect(
                            SignUp.AuthenticateWithRedirectParams.OAuth(provider = OAuthProvider.GOOGLE)
                        )) {
                            is ClerkResult.Success -> {
                                val sessionId = result.value.signIn?.createdSessionId ?: result.value.signUp?.createdSessionId
                                if (sessionId == null) {
                                    message = "Google sign-in needs additional verification. Please try again."
                                } else {
                                    when (Clerk.auth.setActive(sessionId)) {
                                        is ClerkResult.Success -> onClose()
                                        is ClerkResult.Failure -> message = "Could not finish signing in. Please try again."
                                    }
                                }
                            }
                            is ClerkResult.Failure -> message = "Google sign-in did not finish. Please try again."
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        message = "Could not sign in. Check your connection and try again."
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
            } else {
                Image(painterResource(com.clerk.ui.R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (busy) "Signing in…" else "Continue with Google")
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Warning, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
