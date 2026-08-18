package org.draken.usagi.core.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import leakcanary.AppWatcher

abstract class BaseService : LifecycleService() {
	override fun attachBaseContext(newBase: Context) {
		val applicationBase = newBase.applicationContext ?: newBase
		super.attachBaseContext(ContextCompat.getContextForLanguage(applicationBase))
	}

	override fun onDestroy() {
		super.onDestroy()
		AppWatcher.objectWatcher.watch(
			watchedObject = this,
			description = "${javaClass.simpleName} service received Service#onDestroy() callback",
		)
	}
}
