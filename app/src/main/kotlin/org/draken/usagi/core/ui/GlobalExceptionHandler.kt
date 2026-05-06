package org.draken.usagi.core.ui

import android.content.Context
import android.os.Process
import kotlin.system.exitProcess

class GlobalExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stackTrace = throwable.stackTraceToString()
            BaseCrashService.start(context, stackTrace)
            Thread.sleep(250) // cooldown
        } catch (_: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        } finally {
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}
