package org.draken.usagi.settings.sources.manage.plugins.model

import androidx.annotation.StringRes
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.usagi.list.ui.model.ListModel

sealed interface PluginManageItem : ListModel {
	data class Plugin(
		val name: String,
		val repository: String?,
		val installedTag: String?,
		val latestTag: String?,
	) : PluginManageItem {
		val displayName: String
			get() = name.removeSuffix(".jar")

		val hasUpdate: Boolean
			get() = !latestTag.isNullOrBlank() && latestTag != installedTag

		override fun areItemsTheSame(other: ListModel): Boolean = other is Plugin && name == other.name
	}

	data class Tachiyomi(
		val artifact: TachiyomiExtensionArtifact,
		val installed: DirectTachiyomiInstalled?,
		val errorMessage: String? = null,
	) : PluginManageItem {
		val displayName: String get() = artifact.name
		val isInstalled: Boolean get() = installed != null
		val hasUpdate: Boolean get() = installed != null && artifact.versionCode != null && artifact.versionCode > installed.versionCode
		val isCompatible: Boolean get() = artifact.extensionLib == null || artifact.extensionLib in 1.4..1.6
		val sourceSummary: String get() =
			buildList {
				artifact.versionName?.let { add(it) }
				if (artifact.sourceCount > 0) add("${artifact.sourceCount} sources")
				artifact.extensionLib?.let { add("lib $it") }
				installed?.let { add("installed ${it.versionName}") }
			}.joinToString(" • ")

		override fun areItemsTheSame(other: ListModel): Boolean = other is Tachiyomi && artifact.packageName == other.artifact.packageName
	}

	data class Placeholder(
		@field:StringRes val titleResId: Int,
		@field:StringRes val summaryResId: Int?,
	) : PluginManageItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Placeholder && titleResId == other.titleResId && summaryResId == other.summaryResId
	}
}
