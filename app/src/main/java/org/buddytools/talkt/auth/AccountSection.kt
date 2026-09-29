package org.buddytools.talkt.auth

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.buddytools.talkt.ui.Hairline
import org.buddytools.talkt.ui.SurfaceRaised
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import kotlinx.coroutines.launch
import org.buddytools.talkt.BuildConfig
import org.buddytools.talkt.capture.PendingTranscriptionService
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.Warning

@Composable
fun ProfileButton() {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            val user by Clerk.userFlow.collectAsStateWithLifecycle()
            key(user?.id) {
                SubcomposeAsyncImage(
                    model = user?.imageUrl,
                    contentDescription = "Your account",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                    loading = { ProfilePlaceholder() },
                    error = { ProfilePlaceholder() },
                )
            }
        } else {
            ProfilePlaceholder()
        }
    }
    if (expanded) {
        AccountDialog(onClose = { expanded = false })
    }
}

@Composable
private fun ProfilePlaceholder() {
    Icon(Icons.Outlined.AccountCircle, contentDescription = "Your account", tint = Cloud, modifier = Modifier.size(28.dp))
}

@Composable
private fun AccountDialog(onClose: () -> Unit) {
    if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Your account") },
            text = { Text("Sign-in is unavailable in this build. Please try again later.", color = Warning) },
            confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        )
        return
    }
    val user by Clerk.userFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember(user?.id) { mutableStateOf<String?>(null) }
    var busy by remember(user?.id) { mutableStateOf(false) }
    if (user == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    AccountDetailsDialog(
        email = user?.primaryEmailAddress?.emailAddress ?: "Signed in",
        busy = busy,
        message = message,
        onClose = onClose,
        onSignOut = {
            busy = true
            scope.launch {
                try {
                    when (Clerk.auth.signOut()) {
                        is ClerkResult.Success -> {
                            context.stopService(Intent(context, PendingTranscriptionService::class.java))
                            org.buddytools.talkt.wakeword.WakeWordService.stop(context)
                            context.getSystemService(NotificationManager::class.java).cancelAll()
                        }
                        is ClerkResult.Failure -> message = "Could not sign out. Check your connection and try again."
                    }
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
internal fun AccountDetailsDialog(
    email: String,
    busy: Boolean = false,
    message: String? = null,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Your account",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(email, color = Cloud, style = MaterialTheme.typography.bodyMedium)
                    message?.let { Text(it, color = Muted) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), enabled = !busy, onClick = onSignOut) {
                        Text("Sign out")
                    }
                    TextButton(shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), onClick = onClose) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
