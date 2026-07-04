package org.draken.usagi.search.domain

import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.SortOrder

data class SearchResults(
	val listFilter: MangaListFilter,
	val sortOrder: SortOrder,
	val manga: List<Manga>,
)
