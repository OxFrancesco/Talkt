package org.buddytools.talkt.trigger

import java.lang.ref.WeakReference

data class EditorSnapshot(
    val packageName: String,
    val capturedAt: Long,
)

object EditorBridge {
    private var service = WeakReference<TalktAccessibilityService>(null)

    internal fun attach(value: TalktAccessibilityService?) {
        service = WeakReference(value)
    }

    /** False when the accessibility service is off: Android also turns it off whenever Talkt is force-stopped. */
    val isConnected: Boolean get() = service.get() != null

    fun captureTarget(preserveExisting: Boolean = false): EditorSnapshot? =
        service.get()?.captureEditorTarget(preserveExisting)

    fun hasFreshTarget(): Boolean = service.get()?.hasFreshEditorTarget() == true

    fun commit(text: String): Boolean = service.get()?.commitToCapturedEditor(text) == true
}
