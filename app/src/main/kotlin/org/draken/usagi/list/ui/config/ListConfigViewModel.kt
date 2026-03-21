package org.draken.usagi.list.ui.config

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.runBlocking
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.require
import org.draken.usagi.core.util.ext.sortedByOrdinal
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.draken.usagi.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class ListConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val section = savedStateHandle.require<ListConfigSection>(AppRouter.KEY_LIST_SECTION)

	var listMode: ListMode
		get() = when (section) {
			is ListConfigSection.Favorites -> settings.favoritesListMode
			ListConfigSection.History -> settings.historyListMode
			ListConfigSection.Suggestions -> settings.suggestionsListMode
			ListConfigSection.General,
			ListConfigSection.Updated -> settings.listMode
		}
		set(value) {
			when (section) {
				is ListConfigSection.Favorites -> settings.favoritesListMode = value
				ListConfigSection.History -> settings.historyListMode = value
				ListConfigSection.Suggestions -> settings.suggestionsListMode = value
				ListConfigSection.Updated,
				ListConfigSection.General -> settings.listMode = value
			}
		}

	var gridSize: Int
		get() = settings.gridSize
		set(value) {
			settings.gridSize = value
		}

	val isGroupingSupported: Boolean
		get() = section == ListConfigSection.History || section == ListConfigSection.Updated

	val isGroupingAvailable: Boolean
		get() = when (section) {
			ListConfigSection.History -> settings.historySortOrder.isGroupingSupported()
			ListConfigSection.Updated -> true
			else -> false
		}

	var isGroupingEnabled: Boolean
		get() = when (section) {
			ListConfigSection.History -> settings.isHistoryGroupingEnabled
			ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled
			else -> false
		}
		set(value) = when (section) {
			ListConfigSection.History -> settings.isHistoryGroupingEnabled = value
			ListConfigSection.Updated -> settings.isUpdatedGroupingEnabled = value
			else -> Unit
		}

	fun getSortOrders(): List<ListSortOrder>? = when (section) {
		is ListConfigSection.Favorites -> ListSortOrder.FAVORITES
		ListConfigSection.General -> null
		ListConfigSection.History -> ListSortOrder.HISTORY
		ListConfigSection.Suggestions -> ListSortOrder.SUGGESTIONS
		ListConfigSection.Updated -> null
	}?.sortedByOrdinal()

	fun getSelectedSortOrder(): ListSortOrder? = when (section) {
		is ListConfigSection.Favorites -> getCategorySortOrder(section.categoryId)
		ListConfigSection.General -> null
		ListConfigSection.Updated -> null
		ListConfigSection.History -> settings.historySortOrder
		ListConfigSection.Suggestions -> ListSortOrder.RELEVANCE
	}

	fun setSortOrder(position: Int) {
		val value = getSortOrders()?.getOrNull(position) ?: return
		when (section) {
			is ListConfigSection.Favorites -> launchJob {
				if (section.categoryId == NO_ID) {
					settings.allFavoritesSortOrder = value
				} else {
					favouritesRepository.setCategoryOrder(section.categoryId, value)
				}
			}

			ListConfigSection.General -> Unit
			ListConfigSection.History -> settings.historySortOrder = value

			ListConfigSection.Suggestions -> Unit
			ListConfigSection.Updated -> Unit
		}
	}

	private fun getCategorySortOrder(id: Long): ListSortOrder = if (id == NO_ID) {
		settings.allFavoritesSortOrder
	} else runBlocking {
		runCatchingCancellable {
			favouritesRepository.getCategory(id).order
		}.getOrElse {
			settings.allFavoritesSortOrder
		}
	}
}
