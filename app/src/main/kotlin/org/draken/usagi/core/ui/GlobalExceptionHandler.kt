package org.draken.usagi.core.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import org.draken.usagi.core.prefs.AppSettings
import kotlin.system.exitProcess

class GlobalExceptionHandler(
	private val context: Context,
	private val settingsProvider: (() -> AppSettings?)? = null,
	private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
	override fun uncaughtException(
		thread: Thread,
		throwable: Throwable,
	) {
		if (isCrashProcess()) {
			defaultHandler?.uncaughtException(thread, throwable)
			return
		}
		try {
			val stackTrace = runCatching { throwable.stackTraceToString() }.getOrDefault(throwable.localizedMessage ?: "Unknown crash")
			val intent = buildCrashIntent(stackTrace)
			context.startActivity(intent)
			Process.killProcess(Process.myPid())
			exitProcess(10)
		} catch (_: Throwable) {
			defaultHandler?.uncaughtException(thread, throwable)
		}
	}

	private fun isCrashProcess(): Boolean =
		runCatching {
			val pid = Process.myPid()
			val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
			am?.runningAppProcesses?.any { it.pid == pid && it.processName.endsWith(":crash") } == true
		}.getOrDefault(false)

	private fun buildCrashIntent(stackTrace: String): Intent {
		val settings = runCatching { settingsProvider?.invoke() }.getOrNull()
		val styleRes = runCatching { settings?.colorScheme?.styleResId }.getOrDefault(0)
		val isAmoled = runCatching { settings?.isAmoledTheme }.getOrDefault(false)
		val nightMode = runCatching { settings?.theme }.getOrDefault(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

		return Intent(context, AppCrashActivity::class.java).apply {
			putExtra(AppCrashActivity.EXTRA_STACK_TRACE, stackTrace)
			putExtra(AppCrashActivity.EXTRA_THEME_STYLE, styleRes)
			putExtra(AppCrashActivity.EXTRA_THEME_AMOLED, isAmoled)
			putExtra(AppCrashActivity.EXTRA_THEME_NIGHT_MODE, nightMode)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
	}

	companion object {
		fun install(
			context: Context,
			settingsProvider: (() -> AppSettings?)? = null,
		) {
			val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
			if (currentHandler !is GlobalExceptionHandler) {
				val appContext = context.applicationContext ?: context
				Thread.setDefaultUncaughtExceptionHandler(
					GlobalExceptionHandler(appContext, settingsProvider, currentHandler),
				)
			}
		}
	}
}
