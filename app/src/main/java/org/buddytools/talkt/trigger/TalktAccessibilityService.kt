package org.buddytools.talkt.trigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import org.buddytools.talkt.capture.CaptureActivity
import org.buddytools.talkt.capture.CaptureBus
import org.buddytools.talkt.data.TalktSettings

class TalktAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var volumeDown = false
    private var longPressTriggered = false
    private var target: TextTarget? = null

    private val triggerLongPress = Runnable {
        if (!volumeDown) return@Runnable
        longPressTriggered = true
        captureEditorTarget(preserveExisting = true)
        startActivity(CaptureActivity.intent(this, source = "volume", autoStart = true).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        })
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                if (Build.VERSION.SDK_INT >= 33) AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR else 0
        }
        EditorBridge.attach(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        EditorBridge.attach(null)
        target = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val currentTarget = target
            if (currentTarget != null &&
                packageName.isNotBlank() &&
                packageName != currentTarget.packageName &&
                packageName != this.packageName &&
                packageName !in TRANSIENT_SYSTEM_PACKAGES &&
                packageName != keyboardPackage()
            ) {
                Log.i(TAG, "editor target from ${currentTarget.packageName} dropped: $packageName took the screen")
                target = null
            }
        }

        if (event.eventType in EDITOR_EVENTS) {
            val source = event.source
            if (source?.isEditable == true && !source.isPassword) {
                captureEditorTarget(preserveExisting = true)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!TalktSettings(this).volumeTriggerEnabled) {
            handler.removeCallbacks(triggerLongPress)
            volumeDown = false
            if (longPressTriggered) CaptureBus.requestStop()
            longPressTriggered = false
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeDown = true
                    longPressTriggered = false
                    handler.postDelayed(triggerLongPress, HOLD_MILLIS)
                    return false
                }
                return longPressTriggered
            }
            KeyEvent.ACTION_UP -> {
                volumeDown = false
                handler.removeCallbacks(triggerLongPress)
                val consumed = longPressTriggered
                if (longPressTriggered) CaptureBus.requestStop()
                longPressTriggered = false
                return consumed
            }
        }
        return false
    }

    internal fun captureEditorTarget(preserveExisting: Boolean = false): EditorSnapshot? {
        val captured = if (Build.VERSION.SDK_INT >= 33) {
            captureInputConnection() ?: captureNodeTarget()
        } else {
            captureNodeTarget()
        }
        if (captured != null) {
            target = captured
        } else if (!preserveExisting) {
            target = null
        }
        val fresh = target?.takeIf { it.isFresh() }
        Log.i(TAG, "editor target: captured=${captured?.kind ?: "none"} kept=${fresh?.kind ?: "none"} package=${fresh?.packageName ?: "-"}")
        return fresh?.let { EditorSnapshot(it.packageName, it.capturedAt) }
    }

    internal fun hasFreshEditorTarget(): Boolean = target?.isFresh() == true

    internal fun commitToCapturedEditor(text: String): Boolean {
        captureEditorTarget(preserveExisting = true)
        val selected = target ?: run {
            Log.w(TAG, "editor commit skipped: no editor target")
            return false
        }
        if (!selected.isFresh()) {
            Log.w(TAG, "editor commit skipped: target from ${selected.packageName} is stale")
            target = null
            return false
        }
        val committed = runCatching { selected.commit(text) }
            .onFailure { Log.w(TAG, "editor commit threw via ${selected.kind}: ${it.message}") }
            .getOrDefault(false)
        Log.i(TAG, "editor commit via ${selected.kind} into ${selected.packageName}: ${if (committed) "inserted" else "rejected"} (${text.length} chars)")
        target = null
        return committed
    }

    @RequiresApi(33)
    private fun captureInputConnection(): TextTarget? {
        val inputMethod = inputMethod ?: return null
        if (!inputMethod.currentInputStarted) return null
        val info = inputMethod.currentInputEditorInfo ?: return null
        if (!isSafeTextEditor(info)) return null
        val connection = inputMethod.currentInputConnection ?: return null
        val packageName = info.packageName.orEmpty()
        return object : TextTarget {
            override val kind = "ime"
            override val packageName = packageName
            override val capturedAt = System.currentTimeMillis()

            override fun commit(text: String): Boolean {
                connection.commitText(text, 1, null)
                // The a11y input connection reports nothing back; read the editor to confirm
                // the text landed. Editors that cannot report surrounding text are trusted.
                val before = runCatching { connection.getSurroundingText(text.length, 0, 0)?.text?.toString() }.getOrNull()
                return before == null || before.endsWith(text)
            }
        }
    }

    /**
     * The focused editor is usually in the active window, but once the user taps the capture
     * bubble that window is ours, so fall back to scanning every interactive window.
     */
    private fun findInputFocus(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName }
            .mapNotNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun captureNodeTarget(): TextTarget? {
        val node = findInputFocus() ?: return null
        if (!node.isEditable || node.isPassword) return null
        if (!node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return null
        val copy = AccessibilityNodeInfo.obtain(node)
        return object : TextTarget {
            override val kind = "node"
            override val packageName = copy.packageName?.toString().orEmpty()
            override val capturedAt = System.currentTimeMillis()

            override fun commit(text: String): Boolean {
                copy.refresh()
                val original = copy.text?.toString().orEmpty()
                val start = copy.textSelectionStart.coerceIn(0, original.length)
                val end = copy.textSelectionEnd.coerceIn(start, original.length)
                val replacement = original.substring(0, start) + text + original.substring(end)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
                }
                val changed = copy.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (changed) {
                    val selection = Bundle().apply {
                        val cursor = start + text.length
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    }
                    copy.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                }
                copy.recycle()
                return changed
            }
        }
    }

    /** The keyboard showing or hiding is not the user leaving the editor. */
    private fun keyboardPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }

    private fun isSafeTextEditor(info: EditorInfo): Boolean {
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation in PASSWORD_VARIATIONS) return false
        val privateOptions = info.privateImeOptions.orEmpty().lowercase()
        return !privateOptions.contains("payment") && !privateOptions.contains("sensitive")
    }

    private interface TextTarget {
        val kind: String
        val packageName: String
        val capturedAt: Long
        fun commit(text: String): Boolean
        fun isFresh(): Boolean = System.currentTimeMillis() - capturedAt <= TARGET_TTL_MILLIS
    }

    private companion object {
        const val TAG = "Talkt"
        const val HOLD_MILLIS = 550L
        const val TARGET_TTL_MILLIS = 2 * 60 * 1_000L
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val EDITOR_EVENTS = setOf(
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
        )
        val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice",
        )
    }
}
