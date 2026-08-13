package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.usagi.R
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.core.parser.tachiyomi.TachiyomiCatalogSource
import org.draken.usagi.core.parser.tachiyomi.TachiyomiContentRating
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.usagi.list.ui.model.ListModel
import tsuki.model.MangaSource
import java.net.URI
import java.util.Locale

sealed interface SourceCatalogItem : ListModel {
	data class Source(
		val source: MangaSource,
	) : SourceCatalogItem {
		override fun areItemsTheSame(other: ListModel): Boolean = other is Source && other.source == source
	}

	data class Tachiyomi(
		val source: TachiyomiCatalogSource,
		val artifact: TachiyomiExtensionArtifact,
		val installed: DirectTachiyomiInstalled?,
	) : SourceCatalogItem {
		val isInstalled: Boolean get() = installed != null
		val hasUpdate: Boolean get() = installed != null && artifact.versionCode != null && artifact.versionCode > installed.versionCode
		val contentRating: TachiyomiContentRating
			get() = source.contentRating.takeUnless { it == TachiyomiContentRating.UNSPECIFIED } ?: artifact.contentRating
		val isNsfw: Boolean get() = contentRating.isNsfw
		val displayName: String get() = source.name

		fun description(context: Context): String {
			val details =
				listOf(
					contentRatingLabel(context),
					languageDisplayName(context),
				).joinToString(", ")
			return "$details • $pluginName"
		}

		private fun contentRatingLabel(context: Context): String =
			when (contentRating) {
				TachiyomiContentRating.SAFE -> context.getString(R.string.rating_safe)
				TachiyomiContentRating.MIXED -> context.getString(R.string.rating_mixed)
				TachiyomiContentRating.NSFW -> context.getString(R.string.rating_adult)
				TachiyomiContentRating.UNSPECIFIED -> context.getString(R.string.unknown)
			}

		private fun languageDisplayName(context: Context): String {
			if (source.language.equals("all", true)) return context.getString(R.string.various_languages)
			val locale = Locale.forLanguageTag(source.language)
			return locale.getDisplayName(locale).takeIf { it.isNotBlank() && it != source.language } ?: source.language
		}

		private val pluginName: String
			get() =
				runCatching {
					URI(artifact.repositoryUrl)
						.path
						.trim('/')
						.substringBefore('/')
						.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
				}.getOrDefault(artifact.repositoryUrl)

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
