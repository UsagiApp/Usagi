package org.draken.usagi.favourites.ui.categories

import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.requireValue
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.domain.model.Cover
import org.draken.usagi.favourites.ui.categories.adapter.AllCategoriesListModel
import org.draken.usagi.favourites.ui.categories.adapter.CategoryListModel
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import javax.inject.Inject

@HiltViewModel
class FavouritesCategoriesViewModel
	@Inject
	constructor(
		private val repository: FavouritesRepository,
		private val settings: AppSettings,
	) : BaseViewModel() {
		private var commitJob: Job? = null
		private val isActionsEnabled = MutableStateFlow(true)

		val content =
			combine(
				repository.observeCategoriesWithCovers(),
				observeAllCategories(),
				isActionsEnabled,
			) { cats, all, hasActions ->
				cats.toUiList(all, hasActions)
			}.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		fun deleteCategories(ids: Set<Long>) {
			launchJob(Dispatchers.Default) {
				repository.removeCategories(ids)
			}
		}

		fun isEmpty(): Boolean = content.value.none { it is CategoryListModel }

		fun saveOrder(snapshot: List<ListModel>) {
			val prevJob = commitJob
			commitJob =
				launchJob {
					prevJob?.cancelAndJoin()
					val ids =
						snapshot.mapNotNullTo(ArrayList(snapshot.size)) {
							(it as? CategoryListModel)?.category?.id
						}
					if (ids.isNotEmpty()) {
						repository.reorderCategories(ids)
					}
				}
		}

		fun setIsVisible(
			ids: Set<Long>,
			isVisible: Boolean,
		) {
			launchJob(Dispatchers.Default) {
				for (id in ids) {
					repository.updateCategory(id, isVisible)
				}
			}
		}

		fun setActionsEnabled(value: Boolean) {
			isActionsEnabled.value = value
		}

		fun getCategories(ids: LongSet): ArrayList<FavouriteCategory> {
			val items = content.requireValue()
			return items.mapNotNullTo(ArrayList(ids.size)) { item ->
				(item as? CategoryListModel)?.category?.takeIf { it.id in ids }
			}
		}

		private fun Map<FavouriteCategory, List<Cover>>.toUiList(
			allFavorites: Pair<Int, List<Cover>>,
			hasActions: Boolean,
		): List<ListModel> {
			val result = ArrayList<ListModel>(size + 1)
			result.add(
				AllCategoriesListModel(
					mangaCount = allFavorites.first,
					covers = allFavorites.second,
				),
			)
			mapTo(result) { (category, covers) ->
				CategoryListModel(
					mangaCount = covers.size,
					covers = covers.take(3),
					category = category,
					isActionsEnabled = hasActions,
					isTrackerEnabled = settings.isTrackerEnabled && AppSettings.TRACK_FAVOURITES in settings.trackSources,
				)
			}
			return result
		}

		private fun observeAllCategories(): Flow<Pair<Int, List<Cover>>> =
			settings
				.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) {
					allFavoritesSortOrder
				}.mapLatest { order ->
					repository.getAllFavoritesCovers(order, limit = 3)
				}.combine(repository.observeMangaCount()) { covers, count ->
					count to covers
				}
	}
