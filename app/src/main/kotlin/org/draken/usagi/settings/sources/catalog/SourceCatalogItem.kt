package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.tsukimix.core.parser.tachiyomi.model.DirectTachiyomiInstalled
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiCatalogSource
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionArtifact
import org.draken.usagi.R
import org.draken.usagi.core.model.getSummary
import org.draken.usagi.core.model.titleResId
import org.draken.usagi.core.model.unwrap
import org.draken.usagi.list.ui.model.ListModel
import tsuki.model.ContentType
import tsuki.model.MangaSource
import java.net.URI
import java.util.Locale

sealed interface SourceCatalogItem : ListModel {
	data class Source(
		val source: MangaSource,
		val isMultiLanguage: Boolean = false,
	) : SourceCatalogItem {
		fun description(context: Context): String? {
			if (!isMultiLanguage) {
				return source.getSummary(context)
			}
			val type = context.getString(source.contentType.titleResId)
			val lang = context.getString(R.string.various_languages)
			val unwrapped = source.unwrap()
			val pluginLabel =
				(source as? org.draken.usagi.core.model.PluginMangaSource)?.jarName?.removeSuffix(".jar")?.removeSuffix(".apk")
					?: when (unwrapped) {
						is org.draken.tsukimix.core.parser.tachiyomi.model.Manga -> {
							if (unwrapped.isPreInstalled) {
								context.getString(R.string.external_source)
							} else {
								org.draken.usagi.core.model.DirectTachiyomiPluginMetadata
									.get(unwrapped.pkgName)
									?: context.getString(R.string.external_source)
							}
						}

						is org.draken.usagi.core.parser.external.ExternalMangaSource -> {
							context.getString(R.string.external_source)
						}

						else -> {
							null
						}
					}
			return if (pluginLabel != null) {
				"$type, $lang • $pluginLabel"
			} else {
				context.getString(R.string.source_summary_pattern, type, lang)
			}
		}

		override fun areItemsTheSame(other: ListModel): Boolean = other is Source && other.source == source
	}

	data class Tachiyomi(
		val source: TachiyomiCatalogSource,
		val artifact: TachiyomiExtensionArtifact,
		val installed: DirectTachiyomiInstalled?,
		val isLoaded: Boolean,
		val isPreInstalledApk: Boolean,
		val isMultiLanguage: Boolean = false,
		val isInstalling: Boolean = false,
		val customPluginName: String? = null,
	) : SourceCatalogItem {
		val isInstalled: Boolean get() = installed != null

		val hasUpdate: Boolean
			get() {
				val availableVersion = artifact.versionCode ?: return false
				val installedVersion = installed?.versionCode ?: return false
				return availableVersion > installedVersion
			}

		val contentType: ContentType
			get() = source.contentType
		val isNsfw: Boolean get() = contentType == ContentType.HENTAI

		val displayName: String get() = source.name

		fun description(context: Context): String {
			val lang =
				if (isMultiLanguage || source.language.equals("all", true)) {
					context.getString(R.string.various_languages)
				} else {
					languageDisplayName(context)
				}
			val details =
				listOf(
					contentTypeLabel(context),
					lang,
				).joinToString(", ")
			val label =
				if (isPreInstalledApk) {
					context.getString(R.string.external_source)
				} else {
					pluginName
				}
			return "$details • $label"
		}

		private fun contentTypeLabel(context: Context): String =
			when (contentType) {
				ContentType.MANGA -> context.getString(R.string.content_type_manga)
				ContentType.HENTAI -> context.getString(R.string.content_type_hentai)
				else -> context.getString(R.string.unknown)
			}

		private fun languageDisplayName(context: Context): String {
			if (source.language.equals("all", true)) return context.getString(R.string.various_languages)
			val locale = Locale.forLanguageTag(source.language)
			return locale.getDisplayName(locale).takeIf { it.isNotBlank() && it != source.language } ?: source.language
		}

		private val pluginName: String
			get() =
				customPluginName?.trim()?.takeIf { it.isNotBlank() }
					?: org.draken.usagi.core.model.DirectTachiyomiPluginMetadata
						.get(artifact.packageName)
					?: if (artifact.repositoryUrl.startsWith("local:") || artifact.repositoryUrl.startsWith("installed:")) {
						installed
							?.name
							?.removePrefix("Tachiyomi: ")
							?.removePrefix("Tachiyomi - ")
							?.trim()
							?.takeIf { it.isNotBlank() }
							?: artifact.name
								.removePrefix("Tachiyomi: ")
								.removePrefix("Tachiyomi - ")
								.trim()
					} else {
						runCatching {
							val uri = URI(artifact.repositoryUrl)
							val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
							val segments =
								uri.path
									.trim('/')
									.split('/')
									.filter { it.isNotBlank() }
							when {
								host.endsWith(".github.io") -> host.removeSuffix(".github.io")
								segments.isNotEmpty() -> segments.first().replaceFirstChar { it.titlecase(Locale.ROOT) }
								else -> host.ifBlank { artifact.repositoryUrl }
							}
						}.getOrDefault(artifact.repositoryUrl)
					}

		override fun areItemsTheSame(other: ListModel): Boolean = other is Tachiyomi && source.id == other.source.id
	}

	data class Hint(
		@field:DrawableRes val icon: Int,
		@field:StringRes val title: Int,
		@field:StringRes val text: Int,
	) : SourceCatalogItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Hint && other.title == title
	}
}
