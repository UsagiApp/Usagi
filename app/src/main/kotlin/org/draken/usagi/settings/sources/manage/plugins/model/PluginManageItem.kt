package org.draken.usagi.settings.sources.manage.plugins.model

import androidx.annotation.StringRes
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.tsukimix.core.parser.external.model.ExtFailure
import org.draken.tsukimix.core.parser.external.model.ExtInstalled
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

	data class Extension(
		val repositoryUrl: String,
		val repositoryLabel: String,
		val displayName: String,
		val artifacts: List<ExtArtifact>,
		val installed: List<ExtInstalled>,
		val failures: List<ExtFailure>,
		val customName: String? = null,
	) : PluginManageItem {
		val isLocal: Boolean
			get() = repositoryUrl.startsWith("local:") || repositoryUrl.startsWith("installed:")

		val hasFailures: Boolean
			get() = failures.isNotEmpty()

		override fun areItemsTheSame(other: ListModel): Boolean = other is Extension && repositoryUrl == other.repositoryUrl
	}

	data class Placeholder(
		@field:StringRes val titleResId: Int,
		@field:StringRes val summaryResId: Int?,
	) : PluginManageItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Placeholder && titleResId == other.titleResId && summaryResId == other.summaryResId
	}

	data class Loading(
		val targetUrl: String,
	) : PluginManageItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Loading && targetUrl == other.targetUrl
	}
}
