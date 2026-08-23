package org.draken.usagi.favourites.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.draken.usagi.core.util.Event
import org.draken.usagi.favourites.data.FavouriteStageCounts
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder

data class FavouritesPageUiState(
	val selectedStage: FavouriteStage,
	val stageCounts: FavouriteStageCounts?,
	val availableRuleOptions: List<ListFilterOption>,
	val selectedRuleOptions: Set<ListFilterOption>,
	val isOrganizerRefreshing: Boolean,
)

interface FavouritesPage {
	val uiState: StateFlow<FavouritesPageUiState>
	val organizerRefreshResults: Flow<Event<FavouriteOrganizerRefreshResult>?>
	val sortOrder: StateFlow<ListSortOrder?>

	fun setStage(stage: FavouriteStage)

	fun setRuleOption(
		option: ListFilterOption,
		isApplied: Boolean,
	)

	fun clearRuleOptions()

	fun setSortOrder(sortOrder: ListSortOrder)

	fun refreshOrganizer()
}
