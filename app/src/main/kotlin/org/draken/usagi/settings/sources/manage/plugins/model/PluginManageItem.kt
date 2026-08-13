package org.draken.usagi.settings.sources.manage.plugins.model

import androidx.annotation.StringRes
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiFailure
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.usagi.list.ui.model.ListModel
import java.net.URI

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

	/** One imported Tachiyomi/Mihon repository is one external plugin row. */
	data class Tachiyomi(
		val repositoryUrl: String,
		val artifacts: List<TachiyomiExtensionArtifact>,
		val installed: List<DirectTachiyomiInstalled>,
		val failures: List<DirectTachiyomiFailure>,
		val customName: String? = null,
	) : PluginManageItem {
		val repositoryLabel: String
			get() =
				runCatching {
					val uri = URI(repositoryUrl)
					val segments =
						uri.path
							.trim('/')
							.split('/')
							.filter { it.isNotBlank() }
					when (uri.host?.lowercase()) {
						"raw.githubusercontent.com" -> segments.take(2).joinToString("/")
						"github.com", "www.github.com" -> segments.take(2).joinToString("/")
						else -> uri.host.orEmpty().ifBlank { repositoryUrl }
					}
				}.getOrDefault(repositoryUrl)

		val displayName: String
			get() = customName?.trim()?.takeIf { it.isNotBlank() } ?: repositoryLabel.substringBefore('/').ifBlank { "Tachiyomi/Mihon" }

		val extensionCount: Int
			get() = artifacts.size

		val installedCount: Int
			get() = installed.size

		val hasFailures: Boolean
			get() = failures.isNotEmpty()

		override fun areItemsTheSame(other: ListModel): Boolean = other is Tachiyomi && repositoryUrl == other.repositoryUrl
	}

	data class Placeholder(
		@field:StringRes val titleResId: Int,
		@field:StringRes val summaryResId: Int?,
	) : PluginManageItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Placeholder && titleResId == other.titleResId && summaryResId == other.summaryResId
	}
}
