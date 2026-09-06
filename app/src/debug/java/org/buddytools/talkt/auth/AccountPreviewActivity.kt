package org.buddytools.talkt.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.buddytools.talkt.ui.TalktTheme

class AccountPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TalktTheme {
                var result by remember { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize()) {
                    if (result == null) {
                        AccountDetailsDialog(
                            email = "preview@example.com",
                            onClose = { result = "Done tapped" },
                            onSignOut = { result = "Sign out tapped. No session was changed." },
                        )
                    } else {
                        Text(result!!)
                    }
                }
            }
        }
    }
}
