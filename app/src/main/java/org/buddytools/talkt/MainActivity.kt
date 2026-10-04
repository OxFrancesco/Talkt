package org.buddytools.talkt

import android.Manifest
import android.app.role.RoleManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.buddytools.talkt.cabinet.CabinetActivity
import org.buddytools.talkt.capture.PendingTranscriptionService
import org.buddytools.talkt.capture.RecordingStore
import org.buddytools.talkt.data.TalktSettings
import org.buddytools.talkt.trigger.TalktAccessibilityService
import org.buddytools.talkt.ui.Cloud
import org.buddytools.talkt.ui.Ink
import org.buddytools.talkt.ui.Mint
import org.buddytools.talkt.ui.Muted
import org.buddytools.talkt.ui.SurfaceRaised
import org.buddytools.talkt.ui.TalktTheme
import org.buddytools.talkt.ui.Warning
import org.buddytools.talkt.wakeword.WakeWordAvailability
import org.buddytools.talkt.wakeword.WakeWordModel
import org.buddytools.talkt.wakeword.WakeWordPrerequisites
import org.buddytools.talkt.wakeword.WakeWordService

import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.BackHandler
import com.clerk.api.Clerk
import com.clerk.ui.auth.AuthView
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.capture.CaptureActivity

class MainActivity : ComponentActivity() {
    private lateinit var settings: TalktSettings
    private var localState by mutableStateOf(LocalState())

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshLocalState()
    }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshLocalState()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TalktSettings(this)
        refreshLocalState()
        setContent {
            TalktTheme {
                if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Sign-in is unavailable in this build", color = Warning)
                    }
                } else {
                    val initialized by Clerk.isInitialized.collectAsStateWithLifecycle()
                    val user by Clerk.userFlow.collectAsStateWithLifecycle()
                    when {
                        !initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        user == null -> AuthView(isDismissible = false)
                        else -> key(user?.id) {
                            LaunchedEffect(user?.id) { recoverRecordings() }
                            TalktHome(
                                settings = settings,
                                localState = localState,
                                onRecord = {
                                    if (localState.microphone) startActivity(CaptureActivity.intent(this, "app"))
                                    else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onRequestMicrophone = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                onRequestAssistant = ::requestAssistantRole,
                                onRequestUnrestrictedBattery = ::requestUnrestrictedBattery,
                                onWakeWordEnabled = ::setWakeWordEnabled,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalState()
        if (TalktAuth.userId != null && settings.wakeWordEnabled && localState.wakeWordAvailability == WakeWordAvailability.Ready) {
            WakeWordService.start(this)
        }
        // Recordings left behind by a killed bubble get their automatic attempt while we are in front.
        recoverRecordings()
    }

    private fun recoverRecordings() {
        val owner = TalktAuth.userId ?: return
        org.buddytools.talkt.wear.WearInboxWorker.enqueue(this)
        if (CaptureActivity.isActive) return
        val recordings = RecordingStore(filesDir, owner)
        recordings.adoptOrphans()
        if (recordings.pending().isNotEmpty()) PendingTranscriptionService.start(this)
    }

    private fun requestUnrestrictedBattery() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (request.resolveActivity(packageManager) != null) {
            startActivity(request)
        } else {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshLocalState() {
        val manager = getSystemService(AccessibilityManager::class.java)
        val accessibilityEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == TalktAccessibilityService::class.java.name }
        val assistantEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            false
        }
        localState = LocalState(
            microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            accessibility = accessibilityEnabled,
            assistant = assistantEnabled,
            unrestrictedBattery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            wakeWordPrerequisites = WakeWordService.prerequisites(this),
        )
    }

    private fun requestAssistantRole() {
        val sideButtonSettings = Intent(SAMSUNG_SIDE_BUTTON_SETTINGS)
        if (sideButtonSettings.resolveActivity(packageManager) != null) {
            startActivity(sideButtonSettings)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                assistantRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        }

        val defaultAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        val assistantSettingsIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        when {
            defaultAppsIntent.resolveActivity(packageManager) != null -> startActivity(defaultAppsIntent)
            assistantSettingsIntent.resolveActivity(packageManager) != null -> startActivity(assistantSettingsIntent)
            else -> startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun setWakeWordEnabled(enabled: Boolean) {
        settings.wakeWordEnabled = enabled
        if (enabled) {
            WakeWordService.start(this)
            if (Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            WakeWordService.stop(this)
        }
        refreshLocalState()
    }

    private companion object {
        const val SAMSUNG_SIDE_BUTTON_SETTINGS = "com.samsung.android.intent.action.SIDE_KEY_SETTINGS"
    }
}

private data class LocalState(
    val microphone: Boolean = false,
    val accessibility: Boolean = false,
    val assistant: Boolean = false,
    val unrestrictedBattery: Boolean = true,
    val wakeWordPrerequisites: WakeWordPrerequisites = WakeWordPrerequisites(),
) {
    val wakeWordAvailability: WakeWordAvailability
        get() = org.buddytools.talkt.wakeword.WakeWordPolicy.availability(wakeWordPrerequisites)
}

@Composable
private fun TalktSettingsScreen(
    settings: TalktSettings,
    localState: LocalState,
    onRequestMicrophone: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestAssistant: () -> Unit,
    onRequestUnrestrictedBattery: () -> Unit,
    onWakeWordEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var silenceTimeoutSeconds by remember { mutableIntStateOf(settings.silenceTimeoutSeconds) }
    var volumeTriggerEnabled by remember { mutableStateOf(settings.volumeTriggerEnabled) }
    var powerTriggerEnabled by remember { mutableStateOf(settings.powerTriggerEnabled) }
    var wakeWordEnabled by remember { mutableStateOf(settings.wakeWordEnabled) }
    var languageCodes by remember { mutableStateOf(settings.languageCodes) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink).safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Settings",
                    modifier = Modifier.weight(1f),
                    color = Cloud,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onBack, modifier = Modifier.widthIn(min = 52.dp), contentPadding = PaddingValues(0.dp)) { Text("Done") }
            }
        }

        item {
            GroupColumn(shape = GroupShape.Single) {
                org.buddytools.talkt.cloud.CloudBackupSection()
            }
        }

        if (!localState.microphone) {
            item {
                GroupRow(shape = GroupShape.Single, onClick = onRequestMicrophone) {
                    Column(Modifier.weight(1f)) {
                        Text("Microphone access needed", color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Text("Allow", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            item { GroupGap() }
        }
        if (!localState.accessibility) {
            item {
                GroupRow(shape = GroupShape.Single, onClick = onOpenAccessibility) {
                    Column(Modifier.weight(1f)) {
                        Text("Text insertion is off", color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Enable Talkt to insert text into the app you are using.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Text("Enable", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            item { GroupGap() }
        }
        if (!localState.unrestrictedBattery) {
            item {
                GroupRow(shape = GroupShape.Single, onClick = onRequestUnrestrictedBattery) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery may stop Talkt", color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Allow background use for recording shortcuts.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Text("Fix", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            item { GroupGap() }
        }

        item { GroupHeader("Triggers") }
        item {
            GroupRow(
                shape = GroupShape.Top,
                onClick = if (localState.assistant) null else onRequestAssistant,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Power button", color = Cloud, fontSize = 16.sp)
                    if (!localState.assistant) {
                        Text("Tap to set Talkt as assistant", color = Warning, fontSize = 12.sp)
                    }
                }
                Switch(
                    checked = powerTriggerEnabled,
                    onCheckedChange = {
                        powerTriggerEnabled = it
                        settings.powerTriggerEnabled = it
                    },
                )
            }
        }
        item {
            GroupRow(
                shape = GroupShape.Middle,
                onClick = if (localState.accessibility) null else onOpenAccessibility,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Volume Down", color = Cloud, fontSize = 16.sp)
                    if (!localState.accessibility) {
                        Text("Tap to enable accessibility", color = Warning, fontSize = 12.sp)
                    }
                }
                Switch(
                    checked = volumeTriggerEnabled,
                    onCheckedChange = {
                        volumeTriggerEnabled = it
                        settings.volumeTriggerEnabled = it
                    },
                )
            }
        }
        item {
            val setupAction = when {
                !localState.wakeWordPrerequisites.microphoneGranted -> onRequestMicrophone
                !localState.wakeWordPrerequisites.activeAssistant -> onRequestAssistant
                else -> null
            }
            GroupRow(shape = GroupShape.Bottom, onClick = setupAction) {
                Column(Modifier.weight(1f)) {
                    Text("\"${WakeWordModel.phrase}\"", color = Cloud, fontSize = 16.sp)
                    Text(
                        when {
                            !localState.wakeWordPrerequisites.modelAvailable -> "Local wake-word model is missing"
                            !localState.wakeWordPrerequisites.microphoneGranted -> "Tap to allow microphone access"
                            !localState.wakeWordPrerequisites.activeAssistant -> "Tap to set Talkt as assistant"
                            wakeWordEnabled -> "Enabled · processed only on this device"
                            else -> "Wake Talkt by voice, including while locked"
                        },
                        color = if (
                            localState.wakeWordPrerequisites.modelAvailable &&
                            localState.wakeWordPrerequisites.microphoneGranted &&
                            localState.wakeWordPrerequisites.activeAssistant
                        ) Muted else Warning,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    enabled = localState.wakeWordPrerequisites.modelAvailable &&
                        localState.wakeWordPrerequisites.microphoneGranted &&
                        localState.wakeWordPrerequisites.activeAssistant,
                    onCheckedChange = {
                        wakeWordEnabled = it
                        onWakeWordEnabled(it)
                    },
                )
            }
        }

        item { GroupGap() }
        item {
            GroupColumn(shape = GroupShape.Top) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop after silence", color = Cloud, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("${silenceTimeoutSeconds}s", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Slider(
                    modifier = Modifier.fillMaxWidth(),
                    value = silenceTimeoutSeconds.toFloat(),
                    onValueChange = {
                        silenceTimeoutSeconds = it.toInt()
                        settings.silenceTimeoutSeconds = silenceTimeoutSeconds
                    },
                    valueRange = TalktSettings.MIN_SILENCE_TIMEOUT_SECONDS.toFloat()..
                        TalktSettings.MAX_SILENCE_TIMEOUT_SECONDS.toFloat(),
                    steps = TalktSettings.MAX_SILENCE_TIMEOUT_SECONDS -
                        TalktSettings.MIN_SILENCE_TIMEOUT_SECONDS - 1,
                )
            }
        }
        item {
            GroupColumn(shape = GroupShape.Bottom) {
                Text("Languages", color = Cloud, fontSize = 16.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChip("Auto", "", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    LanguageChip("EN", "en", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    LanguageChip("IT", "it", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    LanguageChip("ES", "es", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChip("FR", "fr", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    LanguageChip("DE", "de", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    LanguageChip("PT", "pt", languageCodes, Modifier.weight(1f)) {
                        languageCodes = updatedLanguages(languageCodes, it)
                        settings.languageCodes = languageCodes
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TalktHome(
    settings: TalktSettings,
    localState: LocalState,
    onRecord: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestAssistant: () -> Unit,
    onRequestUnrestrictedBattery: () -> Unit,
    onWakeWordEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf(settings.latestResult) }
    LifecycleResumeEffect(Unit) {
        latest = settings.latestResult
        onPauseOrDispose { }
    }
    BackHandler(showSettings) { showSettings = false }
    if (showSettings) {
        TalktSettingsScreen(settings, localState, onRequestMicrophone, onOpenAccessibility,
            onRequestAssistant, onRequestUnrestrictedBattery, onWakeWordEnabled,
            onBack = { showSettings = false })
        return
    }
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Talkt", color = Cloud, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
            org.buddytools.talkt.auth.ProfileButton()
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            if (latest.isBlank()) {
                Text("Record your first transcript", color = Cloud, fontSize = 24.sp)
                Spacer(Modifier.height(12.dp))
                Text("Your audio is sent to the cloud for transcription.", color = Muted, fontSize = 16.sp)
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(latest, color = Cloud, fontSize = 22.sp, maxLines = 12, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Talkt transcript", latest))
                }) { Text("Copy") }
            }
        }
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (localState.microphone) "Record" else "Enable microphone", fontSize = 18.sp)
        }
        TextButton(onClick = { context.startActivity(Intent(context, CabinetActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text("History", fontSize = 16.sp)
        }
        TextButton(onClick = { context.startActivity(Intent(context, org.buddytools.talkt.snippets.SnippetsActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Snippets", fontSize = 16.sp)
        }
    }
}

private enum class GroupShape(val top: Int, val bottom: Int) {
    Top(22, 6),
    Middle(6, 6),
    Bottom(6, 22),
    Single(22, 22),
}

@Composable
private fun groupShape(shape: GroupShape) = RoundedCornerShape(
    topStart = shape.top.dp,
    topEnd = shape.top.dp,
    bottomStart = shape.bottom.dp,
    bottomEnd = shape.bottom.dp,
)

@Composable
private fun GroupRow(shape: GroupShape, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape(shape))
            .background(SurfaceRaised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun GroupColumn(shape: GroupShape, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape(shape))
            .background(SurfaceRaised)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun GroupGap() {
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LanguageChip(
    label: String,
    code: String,
    selected: Set<String>,
    modifier: Modifier = Modifier,
    onToggle: (String) -> Unit,
) {
    FilterChip(
        selected = if (code.isEmpty()) selected.isEmpty() else code in selected,
        onClick = { onToggle(code) },
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp)
            }
        },
        modifier = modifier,
    )
}

private fun updatedLanguages(current: Set<String>, toggled: String): Set<String> = when {
    toggled.isEmpty() -> emptySet()
    toggled in current -> emptySet()
    else -> setOf(toggled)
}
