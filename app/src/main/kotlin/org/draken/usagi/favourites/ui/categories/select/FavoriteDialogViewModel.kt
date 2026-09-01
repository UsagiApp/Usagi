package org.draken.usagi.favourites.ui.categories.select

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.model.ids
import org.draken.usagi.core.model.parcelable.ParcelableManga
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.require
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.ui.categories.select.model.FavoriteSelectionAction
import org.draken.usagi.favourites.ui.categories.select.model.MangaCategoryItem
import org.draken.usagi.favourites.ui.categories.select.model.buildFavoriteSelectionItems
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel
	@Inject
	constructor(
		savedStateHandle: SavedStateHandle,
		private val favouritesRepository: FavouritesRepository,
		settings: AppSettings,
	) : BaseViewModel() {
		val manga =
			savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
				it.manga
			}

		private val refreshTrigger = MutableStateFlow(Any())
		val content =
			combine(
				favouritesRepository.observeCategories(),
				refreshTrigger,
				settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
			) { categories, _, tracker ->
				mapList(categories, tracker)
			}.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		fun setChecked(
			item: MangaCategoryItem,
			isChecked: Boolean,
		) {
			launchJob(Dispatchers.Default) {
				when (val action = item.actionFor(isChecked)) {
					FavoriteSelectionAction.AddToFavorites -> favouritesRepository.addToFavourites(manga)
					FavoriteSelectionAction.RemoveFromFavorites -> favouritesRepository.removeFromFavourites(manga.ids())
					is FavoriteSelectionAction.AddToCategory -> favouritesRepository.addToCategory(action.categoryId, manga)
					is FavoriteSelectionAction.RemoveFromCategory -> favouritesRepository.removeFromCategory(action.categoryId, manga.ids())
				}
				refreshTrigger.value = Any()
			}
		}

		private suspend fun mapList(
			categories: List<FavouriteCategory>,
			tracker: Boolean,
		): List<ListModel> {
			var globalMembershipCount = 0
			val categoryMembershipCounts = categories.associate { it.id to 0 }.toMutableMap()
			for (m in manga) {
				if (favouritesRepository.isFavorite(m.id)) {
					globalMembershipCount++
				}
				favouritesRepository.getCategoriesIds(m.id).forEach { id ->
					categoryMembershipCounts.computeIfPresent(id) { _, count -> count + 1 }
				}
			}
			return buildFavoriteSelectionItems(
				categories = categories,
				globalMembershipCount = globalMembershipCount,
				categoryMembershipCounts = categoryMembershipCounts,
				mangaCount = manga.size,
				isTrackerEnabled = tracker,
			)
		}
	}
