package org.draken.usagi.favourites.ui

import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder

interface FavouritesOptionsHost {
	fun showFavouritesOptions()

	fun currentFavouritesOptions(): FavouritesPageUiState?

	fun currentFavouritesSortOrder(): ListSortOrder?

	fun applyFavouritesFilters(options: Set<ListFilterOption>)

	fun setFavouritesSortOrder(sortOrder: ListSortOrder)

	fun openSmartFolders()
}
