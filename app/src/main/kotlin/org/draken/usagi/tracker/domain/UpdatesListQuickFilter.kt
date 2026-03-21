package org.draken.usagi.tracker.domain

import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.MangaListQuickFilter
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
) : MangaListQuickFilter(settings) {

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
		favouritesRepository.getMostUpdatedCategories(
			limit = 4,
		).map {
			ListFilterOption.Favorite(it)
		}
}
