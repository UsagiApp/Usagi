package org.draken.usagi.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import org.draken.usagi.R
import org.draken.usagi.core.ui.dialog.buildAlertDialog

class BaseCrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: NO_TRACE
        buildAlertDialog(this) {
            setTitle(R.string.error_occurred)
            setMessage(R.string.crash_text)
            setIcon(R.drawable.ic_alert_outline)
            setCancelable(false)
            setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            setPositiveButton(android.R.string.copy) { _, _ ->
                val clipboard = getSystemService<ClipboardManager>()
                clipboard?.setPrimaryClip(ClipData.newPlainText("crash_log", stackTrace))
                finish()
            }
        }.show()
    }

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
        const val NO_TRACE = "noStackTrace"
    }
}
