package org.draken.usagi.settings.sources.manage.plugins

import androidx.annotation.StringRes
import org.draken.usagi.list.ui.model.ListModel

sealed interface PluginManageItem : ListModel {

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

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Plugin && jarName == other.jarName
		}
	}

	data class Placeholder(
		@field:StringRes val titleResId: Int,
		@field:StringRes val summaryResId: Int?,
	) : PluginManageItem {
		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Placeholder && titleResId == other.titleResId && summaryResId == other.summaryResId
		}
	}
}
