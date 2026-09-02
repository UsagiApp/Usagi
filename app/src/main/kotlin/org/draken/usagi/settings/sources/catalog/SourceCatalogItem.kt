package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.tsukimix.core.parser.external.model.ExtInstalled
import org.draken.tsukimix.core.parser.external.model.ExtSource
import org.draken.usagi.R
import org.draken.usagi.core.model.DirectExternalPluginMetadata
import org.draken.usagi.core.model.PluginMangaSource
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
			if (!isMultiLanguage) return source.getSummary(context)
			val type = context.getString(source.contentType.titleResId)
			val lang = context.getString(R.string.various_languages)
			val pLabel =
				(source as? PluginMangaSource)?.jarName?.removeSuffix(".jar")?.removeSuffix(".apk")
					?: when (val u = source.unwrap()) {
						is org.draken.tsukimix.core.parser.external.model.Manga -> {
							if (u.isPreInstalled) {
								context.getString(R.string.external_source)
							} else {
								DirectExternalPluginMetadata.get(u.pkgName)
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
			return if (pLabel != null) {
				"$type, $lang • $pLabel"
			} else {
				context.getString(R.string.source_summary_pattern, type, lang)
			}
		}

		override fun areItemsTheSame(other: ListModel) = other is Source && other.source == source
	}

	data class Extension(
		val source: ExtSource,
		val artifact: ExtArtifact,
		val installed: ExtInstalled?,
		val isLoaded: Boolean,
		val isPreInstalledApk: Boolean,
		val isMultiLanguage: Boolean = false,
		val isInstalling: Boolean = false,
		val customPluginName: String? = null,
	) : SourceCatalogItem {
		val isInstalled get() = installed != null
		val hasUpdate get() = (artifact.versionCode ?: 0) > (installed?.versionCode ?: 0)
		val contentType get() = source.contentType
		val isNsfw get() = contentType == ContentType.HENTAI
		val displayName get() = source.name

		fun description(context: Context): String {
			val lang =
				if (isMultiLanguage || source.language.equals("all", true)) {
					context.getString(R.string.various_languages)
				} else {
					languageDisplayName(context)
				}
			val typeLabel =
				when (contentType) {
					ContentType.MANGA -> context.getString(R.string.content_type_manga)
					ContentType.HENTAI -> context.getString(R.string.content_type_hentai)
					else -> context.getString(R.string.unknown)
				}
			val label = if (isPreInstalledApk) context.getString(R.string.external_source) else pluginName
			return "$typeLabel, $lang • $label"
		}

		private fun languageDisplayName(context: Context): String {
			if (source.language.equals("all", true)) return context.getString(R.string.various_languages)
			val loc = Locale.forLanguageTag(source.language)
			return loc.getDisplayName(loc).takeIf { it.isNotBlank() && it != source.language } ?: source.language
		}

		private val pluginName: String
			get() =
				customPluginName?.trim()?.takeIf { it.isNotBlank() }
					?: DirectExternalPluginMetadata.get(artifact.packageName)
					?: if (artifact.repositoryUrl.startsWith("local:") || artifact.repositoryUrl.startsWith("installed:")) {
						installed
							?.name
							?.removePrefix("Extension: ")
							?.removePrefix("Extension - ")
							?.trim()
							?.takeIf { it.isNotBlank() }
							?: artifact.name
								.removePrefix("Extension: ")
								.removePrefix("Extension - ")
								.trim()
					} else {
						runCatching {
							val uri = URI(artifact.repositoryUrl)
							val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
							val segs =
								uri.path
									.trim('/')
									.split('/')
									.filter { it.isNotBlank() }
							when {
								host.endsWith(".github.io") -> host.removeSuffix(".github.io")
								segs.isNotEmpty() -> segs.first().replaceFirstChar { it.titlecase(Locale.ROOT) }
								else -> host.ifBlank { artifact.repositoryUrl }
							}
						}.getOrDefault(artifact.repositoryUrl)
					}

		override fun areItemsTheSame(other: ListModel) = other is Extension && source.id == other.source.id
	}

	data class Hint(
		@field:DrawableRes val icon: Int,
		@field:StringRes val title: Int,
		@field:StringRes val text: Int,
	) : SourceCatalogItem {
		override fun areItemsTheSame(other: ListModel) = other is Hint && other.title == title
	}
}
