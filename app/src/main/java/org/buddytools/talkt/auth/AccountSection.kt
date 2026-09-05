package org.buddytools.talkt.auth

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.ui.auth.AuthView
import kotlinx.coroutines.launch
import org.buddytools.talkt.BuildConfig
import org.buddytools.talkt.agent.AgentRunService
import org.buddytools.talkt.data.TalktApi
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.Warning

@Composable
fun AccountSection(settings: TalktSettings, onSignIn: () -> Unit) {
    if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) {
        Text("Sign-in is unavailable in this build. Device transcription still works.", color = Warning)
        return
    }
    val initialized by Clerk.isInitialized.collectAsStateWithLifecycle()
    val user by Clerk.userFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember(user?.id) { mutableStateOf("") }
    var message by remember(user?.id) { mutableStateOf<String?>(null) }
    var connected by remember(user?.id) { mutableStateOf(false) }
    var busy by remember(user?.id) { mutableStateOf(false) }
    LaunchedEffect(initialized, user?.id) {
        if (initialized && user != null) {
            runCatching { TalktApi(settings).status() }
                .onSuccess { connected = it.connected }
                .onFailure { message = it.message }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your account", color = Cloud)
        when {
            !initialized -> Text("Connecting to sign-in…", color = Muted)
            user == null -> {
                Text("Sign in to use your private cloud agent. Device transcription works without an account.", color = Muted)
                Button(onClick = onSignIn) { Text("Sign in or create account") }
            }
            else -> {
                Text(user?.primaryEmailAddress?.emailAddress ?: "Signed in", color = Cloud)
                Text(if (connected) "OpenRouter connected to your account" else "Connect OpenRouter to use your agent", color = Muted)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("OpenRouter API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            TalktApi(settings).connectOpenRouter(key.trim())
                            key = ""
                            connected = true
                            message = "Your agent is ready"
                        } catch (error: Exception) {
                            message = error.message
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Connecting…" else if (connected) "Replace key" else "Connect") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            when (Clerk.auth.signOut()) {
                                is ClerkResult.Success -> {
                                    context.stopService(Intent(context, AgentRunService::class.java))
                                    context.getSystemService(NotificationManager::class.java).cancelAll()
                                }
                                is ClerkResult.Failure -> message = "Could not sign out. Check your connection and try again."
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Sign out") }
            }
        }
        message?.let { Text(it, color = Muted) }
    }
}

@Composable
fun TalktSignIn(onClose: () -> Unit) {
    val initialized by Clerk.isInitialized.collectAsStateWithLifecycle()
    if (initialized) {
        AuthView(
            onAuthComplete = onClose,
            onDismiss = onClose,
            preferGoogleOneTap = false,
            startSocialOAuthAsSignUp = true,
        )
    } else {
        CircularProgressIndicator()
    }
}
