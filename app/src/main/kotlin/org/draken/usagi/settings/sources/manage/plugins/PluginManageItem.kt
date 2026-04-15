package org.draken.usagi.settings.sources.manage.plugins

import androidx.annotation.StringRes
import org.draken.usagi.list.ui.model.ListModel

sealed interface PluginManageItem : ListModel {

	data class Plugin(
		val jarName: String,
		val repository: String?,
		val installedTag: String?,
		val latestTag: String?,
		val isTachiyomiRepo: Boolean,
	) : PluginManageItem {

		val displayName: String
			get() = if (isTachiyomiRepo) jarName else jarName.removePluginFileSuffix()

		val hasUpdate: Boolean
			get() = !isTachiyomiRepo && !latestTag.isNullOrBlank() && latestTag != installedTag

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Plugin && jarName == other.jarName && isTachiyomiRepo == other.isTachiyomiRepo
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

private fun String.removePluginFileSuffix(): String {
	val dot = lastIndexOf('.')
	if (dot <= 0) return this
	return when (substring(dot).lowercase()) {
		".jar", ".apk" -> substring(0, dot)
		else -> this
	}
}
