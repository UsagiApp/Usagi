package org.draken.usagi.settings.sources.manage.plugins

import androidx.annotation.StringRes

sealed interface PluginManageItem {

	data class Plugin(
		val jarName: String,
		val repository: String?,
		val installedTag: String?,
		val latestTag: String?,
	) : PluginManageItem {

		val displayName: String
			get() = jarName.removeSuffix(".jar")

		val hasUpdate: Boolean
			get() = !latestTag.isNullOrBlank() && latestTag != installedTag
	}

	data class Placeholder(
		@field:StringRes val titleResId: Int,
		@field:StringRes val summaryResId: Int?,
	) : PluginManageItem
}
