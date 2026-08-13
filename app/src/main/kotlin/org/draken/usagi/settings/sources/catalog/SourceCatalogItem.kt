package org.draken.usagi.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.core.parser.tachiyomi.TachiyomiCatalogSource
import org.draken.usagi.core.parser.tachiyomi.TachiyomiContentRating
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.usagi.list.ui.model.ListModel
import tsuki.model.MangaSource

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
		val description: String
			get() =
				buildList {
					add(source.language)
					artifact.name.takeIf { it != source.name }?.let(::add)
					artifact.versionName?.let { add("v$it") }
					when {
						hasUpdate -> add("update available")
						isInstalled -> add("installed")
					}
				}.joinToString(" • ")

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
