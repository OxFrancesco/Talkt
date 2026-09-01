package org.buddytools.talkt

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class EditorProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = EditText(this).apply {
            hint = "Message"
            textSize = 20f
            setPadding(48, 48, 48, 48)
        }
        setContentView(editor)
        editor.requestFocus()
        editor.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(
                editor,
                InputMethodManager.SHOW_IMPLICIT,
            )
        }
    }
}
