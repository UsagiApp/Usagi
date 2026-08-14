package org.draken.usagi.core.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

abstract class BaseService : LifecycleService() {
	override fun attachBaseContext(newBase: Context) {
		val applicationBase = newBase.applicationContext ?: newBase
		super.attachBaseContext(ContextCompat.getContextForLanguage(applicationBase))
	}
}
