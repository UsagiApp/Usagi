package org.draken.usagi.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoSource
import org.draken.usagi.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed interface SourceCatalogItem : ListModel {

	data class Source(
		val source: MangaSource,
		val installableRepoSource: TachiyomiRepoSource? = null,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Source &&
				other.source.name == source.name &&
				other.installableRepoSource?.key == installableRepoSource?.key
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}
