package org.draken.usagi.core.ui

import android.content.Context
import android.content.Intent
import android.os.Process
import org.draken.usagi.core.prefs.AppSettings
import kotlin.system.exitProcess

class GlobalExceptionHandler(
    private val context: Context,
    private val settings: AppSettings,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val intent = buildCrashIntent(throwable.stackTraceToString())
            context.startActivity(intent)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        } catch (_: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashIntent(stackTrace: String) =
        Intent(context, AppCrashActivity::class.java).apply {
            putExtra(AppCrashActivity.EXTRA_STACK_TRACE, stackTrace)
            putExtra(AppCrashActivity.EXTRA_THEME_STYLE, settings.colorScheme.styleResId)
            putExtra(AppCrashActivity.EXTRA_THEME_AMOLED, settings.isAmoledTheme)
            putExtra(AppCrashActivity.EXTRA_THEME_NIGHT_MODE, settings.theme)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
}
