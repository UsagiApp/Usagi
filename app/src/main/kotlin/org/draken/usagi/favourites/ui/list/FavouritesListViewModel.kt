package org.draken.usagi.favourites.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.parser.MangaDataRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.flattenLatest
import org.draken.usagi.favourites.data.FavouriteStageCounts
import org.draken.usagi.favourites.domain.FavoritesListQuickFilter
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.domain.RefreshFavouriteOrganizerUseCase
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFolderRulesResult
import org.draken.usagi.favourites.ui.FavouritesPageUiState
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.KEY_SCOPE_TYPE
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.KEY_STAGE
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.SCOPE_ALL
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.SCOPE_CATEGORY
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.SCOPE_SMART_FOLDER
import org.draken.usagi.history.domain.MarkAsReadUseCase
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.list.domain.MangaListMapper
import org.draken.usagi.list.domain.QuickFilterListener
import org.draken.usagi.list.ui.MangaListViewModel
import org.draken.usagi.list.ui.model.EmptyState
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import org.draken.usagi.list.ui.model.MangaListModel
import org.draken.usagi.list.ui.model.toErrorState
import org.draken.usagi.local.data.LocalStorageChanges
import org.draken.usagi.local.domain.model.LocalManga
import tsuki.model.Manga
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 16

@HiltViewModel
class FavouritesListViewModel
	@Inject
	constructor(
		private val savedStateHandle: SavedStateHandle,
		private val repository: FavouritesRepository,
		private val mangaListMapper: MangaListMapper,
		private val markAsReadUseCase: MarkAsReadUseCase,
		private val refreshFavouriteOrganizerUseCase: RefreshFavouriteOrganizerUseCase,
		quickFilterFactory: FavoritesListQuickFilter.Factory,
		settings: AppSettings,
		mangaDataRepository: MangaDataRepository,
		@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges),
		QuickFilterListener {
		val scope: FavouriteScope = savedStateHandle.toFavouriteScope()
		val categoryId: Long = (scope as? FavouriteScope.Category)?.id ?: NO_ID
		private val quickFilter = quickFilterFactory.create(categoryId)
		private val refreshTrigger = MutableStateFlow(Any())
		private val mutableOrganizerRefreshing = MutableStateFlow(false)
		private val organizerAutoRefreshGate = FavouriteOrganizerAutoRefreshGate()
		private val limit = MutableStateFlow(PAGE_SIZE)
		private val mutableSelectedStage =
			MutableStateFlow(
				savedStateHandle.get<String>(KEY_STAGE)?.let(FavouriteStage::valueOf) ?: FavouriteStage.ALL,
			)
		private val isPaginationReady = AtomicBoolean(false)
		val selectedStage = mutableSelectedStage.asStateFlow()
		val selectedRuleOptions = quickFilter.appliedOptions
		val onOrganizerRefreshed = MutableEventFlow<FavouriteOrganizerRefreshResult>()
		private val persistentRules =
			when (val currentScope = scope) {
				is FavouriteScope.SmartFolder -> {
					repository.observeSmartFolder(currentScope.id).map { folder ->
						(folder?.rules as? SmartFolderRulesResult.Success)?.rules
					}
				}

				FavouriteScope.All,
				is FavouriteScope.Category,
				-> {
					flowOf(null)
				}
			}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)
		val availableRuleOptions =
			persistentRules
				.mapLatest { rules: SmartFolderRules? ->
					quickFilter.availableOptions(rules).also(quickFilter::retainOptions)
				}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

		val stageCounts =
			repository
				.observeStageCounts(scope)
				.map<FavouriteStageCounts, FavouriteStageCounts?> { counts -> counts }
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)
		val pageUiState =
			combine(
				selectedStage,
				stageCounts,
				availableRuleOptions,
				selectedRuleOptions,
			) { selectedStage, stageCounts, availableRuleOptions, selectedRuleOptions ->
				FavouritesPageUiState(
					selectedStage = selectedStage,
					stageCounts = stageCounts,
					availableRuleOptions = availableRuleOptions,
					selectedRuleOptions = selectedRuleOptions,
				)
			}.stateIn(
				viewModelScope + Dispatchers.Default,
				SharingStarted.Eagerly,
				FavouritesPageUiState(
					selectedStage = selectedStage.value,
					stageCounts = stageCounts.value,
					availableRuleOptions = availableRuleOptions.value,
					selectedRuleOptions = selectedRuleOptions.value,
				),
			)

		override val listMode =
			settings
				.observeAsFlow(AppSettings.KEY_LIST_MODE_FAVORITES) { favoritesListMode }
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.favoritesListMode)

		val sortOrder: StateFlow<ListSortOrder?> =
			when (val currentScope = scope) {
				FavouriteScope.All -> {
					settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) {
						allFavoritesSortOrder
					}
				}

				is FavouriteScope.Category -> {
					repository
						.observeCategory(categoryId)
						.withErrorHandling()
						.map { it?.order }
				}

				is FavouriteScope.SmartFolder -> {
					repository
						.observeSmartFolder(currentScope.id)
						.withErrorHandling()
						.map { it?.listOrder }
				}
			}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

		override val content =
			combine(
				observeFavorites(),
				quickFilter.appliedOptions,
				observeListModeWithTriggers(),
				refreshTrigger,
			) { list, filters, mode, _ ->
				list.mapList(mode, filters)
			}.distinctUntilChanged()
				.onEach {
					isPaginationReady.set(true)
				}.catch {
					emit(listOf(it.toErrorState(canRetry = false)))
				}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		init {
			maybeAutoRefreshOrganizer(mutableSelectedStage.value)
		}

		override fun onRefresh() {
			refreshTrigger.value = Any()
		}

		override fun onRetry() = Unit

		override fun setFilterOption(
			option: ListFilterOption,
			isApplied: Boolean,
		) = quickFilter.setFilterOption(option, isApplied)

		override fun toggleFilterOption(option: ListFilterOption) = quickFilter.toggleFilterOption(option)

		override fun clearFilter() = quickFilter.clearFilter()

		private fun refreshOrganizer() {
			if (mutableOrganizerRefreshing.value) return
			launchJob(Dispatchers.Default) {
				mutableOrganizerRefreshing.value = true
				try {
					val result = refreshFavouriteOrganizerUseCase(scope)
					onOrganizerRefreshed.call(result)
					onRefresh()
				} finally {
					mutableOrganizerRefreshing.value = false
				}
			}
		}

		fun markAsRead(items: Set<Manga>) {
			launchLoadingJob(Dispatchers.Default) {
				markAsReadUseCase(items)
				onRefresh()
			}
		}

		fun removeFromFavourites(ids: Set<Long>) {
			if (ids.isEmpty()) {
				return
			}
			launchJob(Dispatchers.Default) {
				val handle =
					when (val currentScope = scope) {
						FavouriteScope.All,
						is FavouriteScope.SmartFolder,
						-> repository.removeFromFavourites(ids)

						is FavouriteScope.Category -> repository.removeFromCategory(currentScope.id, ids)
					}
				onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
			}
		}

		fun setSortOrder(order: ListSortOrder) {
			launchJob {
				when (val currentScope = scope) {
					FavouriteScope.All -> Unit
					is FavouriteScope.Category -> repository.setCategoryOrder(currentScope.id, order)
					is FavouriteScope.SmartFolder -> repository.setSmartFolderOrder(currentScope.id, order)
				}
			}
		}

		fun setStage(stage: FavouriteStage) {
			mutableSelectedStage.value = stage
			savedStateHandle[KEY_STAGE] = stage.name
			maybeAutoRefreshOrganizer(stage)
		}

		private fun maybeAutoRefreshOrganizer(stage: FavouriteStage) {
			if (organizerAutoRefreshGate.shouldRefresh(stage)) {
				refreshOrganizer()
			}
		}

		fun saveMangaOrder(items: List<ListModel>) {
			val currentScope = scope as? FavouriteScope.Category ?: return
			val mangaIds = items.mapNotNull { (it as? MangaListModel)?.id }
			launchJob(Dispatchers.IO) {
				kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
					repository.reorderManga(currentScope.id, mangaIds)
				}
			}
		}

		fun requestMoreItems() {
			if (isPaginationReady.compareAndSet(true, false)) {
				limit.value += PAGE_SIZE
			}
		}

		private suspend fun List<Manga>.mapList(
			mode: ListMode,
			filters: Set<ListFilterOption>,
		): List<ListModel> {
			if (isEmpty()) {
				return if (filters.isEmpty()) {
					listOf(getEmptyState(hasFilters = false))
				} else {
					listOf(getEmptyState(hasFilters = true))
				}
			}
			val result = ArrayList<ListModel>(size)
			mangaListMapper.toListModelList(result, this, mode, MangaListMapper.NO_FAVORITE)
			return result
		}

		private fun observeFavorites() =
			combine(
				sortOrder.filterNotNull(),
				quickFilter.appliedOptions.combineWithSettings(),
				limit,
				selectedStage,
			) { order, filters, limit, stage ->
				isPaginationReady.set(false)
				repository.observeAll(scope, stage, order, filters, limit)
			}.flattenLatest()

		private fun getEmptyState(hasFilters: Boolean) =
			if (hasFilters) {
				EmptyState(
					icon = R.drawable.ic_empty_favourites,
					textPrimary = R.string.nothing_found,
					textSecondary = R.string.text_empty_holder_secondary_filtered,
					actionStringRes = R.string.reset_filter,
				)
			} else {
				EmptyState(
					icon = R.drawable.ic_empty_favourites,
					textPrimary = R.string.text_empty_holder_primary,
					textSecondary =
						if (scope == FavouriteScope.All) {
							R.string.you_have_not_favourites_yet
						} else {
							R.string.favourites_category_empty
						},
					actionStringRes = 0,
				)
			}

		private fun SavedStateHandle.toFavouriteScope(): FavouriteScope =
			when (requireNotNull(get<String>(KEY_SCOPE_TYPE)) { "Favourite scope type is missing" }) {
				SCOPE_ALL -> FavouriteScope.All
				SCOPE_CATEGORY -> FavouriteScope.Category(requireNotNull(get<Long>(AppRouter.KEY_ID)))
				SCOPE_SMART_FOLDER -> FavouriteScope.SmartFolder(requireNotNull(get<Long>(AppRouter.KEY_ID)))
				else -> error("Unsupported favourite scope type")
			}
	}
