package org.draken.usagi.filter.ui

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.draken.usagi.core.model.MangaSource
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.prefs.SourceSettings
import org.draken.usagi.core.util.LocaleComparator
import org.draken.usagi.core.util.ext.asFlow
import org.draken.usagi.core.util.ext.lifecycleScope
import org.draken.usagi.core.util.ext.sortedByOrdinal
import org.draken.usagi.core.util.ext.sortedWithSafe
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.draken.usagi.filter.data.PersistableFilter
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.filter.ui.model.FilterProperty
import org.draken.usagi.filter.ui.tags.TagTitleComparator
import org.draken.usagi.filter.ui.external.FilterHost
import org.draken.usagi.filter.ui.external.FilterMapper
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Demographic
import tsuki.model.MangaListFilter
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import tsuki.model.YEAR_MIN
import tsuki.util.ifZero
import tsuki.util.nullIfEmpty
import tsuki.util.suspendlazy.suspendLazy
import org.draken.usagi.remotelist.ui.RemoteListFragment
import org.draken.usagi.search.domain.MangaSearchRepository
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@ViewModelScoped
class FilterCoordinator @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mangaRepositoryFactory: MangaRepository.Factory,
    private val searchRepository: MangaSearchRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    @ApplicationContext context: Context,
    lifecycle: ViewModelLifecycle,
) {

    private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
    private val repository = mangaRepositoryFactory.create(MangaSource(savedStateHandle[RemoteListFragment.ARG_SOURCE]))
    private val sourceLocale: String? = null
    private val sourceSettings = SourceSettings(context, repository.source)

    private val listFilter = MutableStateFlow(restoreSortFilter())
    private val sourceOrder = MutableStateFlow(repository.defaultSortOrder)
    private val sortLabel = MutableStateFlow<String?>(null)

    private val availableSortOrders = repository.sortOrders
    private val filterOptions = suspendLazy { repository.getFilterOptions() }

    val capabilities = repository.filterCapabilities

    val mangaSource: MangaSource
        get() = repository.source

    val isFilterApplied: Boolean
        get() = listFilter.value.isNotEmpty()

    val query: StateFlow<String?> = listFilter.map { it.query }
        .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    val sortOrder: StateFlow<FilterProperty<SortOrder>> = sourceOrder.map { selected ->
        FilterProperty(
            availableItems = availableSortOrders.sortedByOrdinal(),
            selectedItem = selected,
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val tags: StateFlow<FilterProperty<MangaTag>> = combine(
        getTopTags(TAGS_LIMIT),
        listFilter.distinctUntilChangedBy { it.tags },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.addFirstDistinct(selected.tags),
                    selectedItems = selected.tags,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val tagsExcluded: StateFlow<FilterProperty<MangaTag>> = if (capabilities.isTagsExclusionSupported) {
        combine(
            getBottomTags(TAGS_LIMIT),
            listFilter.distinctUntilChangedBy { it.tagsExclude },
        ) { available, selected ->
            available.fold(
                onSuccess = {
                    FilterProperty(
                        availableItems = it.addFirstDistinct(selected.tagsExclude),
                        selectedItems = selected.tagsExclude,
                    )
                },
                onFailure = {
                    FilterProperty.error(it)
                },
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val authors: StateFlow<FilterProperty<String>> = if (capabilities.isAuthorSearchSupported) {
        combine(
            flow { emit(searchRepository.getAuthors(repository.source, TAGS_LIMIT)) },
            listFilter.distinctUntilChangedBy { it.author },
        ) { available, selected ->
            FilterProperty(
                availableItems = available,
                selectedItems = setOfNotNull(selected.author),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val states: StateFlow<FilterProperty<MangaState>> = combine(
        filterOptions.asFlow(),
        listFilter.distinctUntilChangedBy { it.states },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableStates.sortedByOrdinal(),
                    selectedItems = selected.states,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val contentRating: StateFlow<FilterProperty<ContentRating>> = combine(
        filterOptions.asFlow(),
        listFilter.distinctUntilChangedBy { it.contentRating },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableContentRating.sortedByOrdinal(),
                    selectedItems = selected.contentRating,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val contentTypes: StateFlow<FilterProperty<ContentType>> = combine(
        filterOptions.asFlow(),
        listFilter.distinctUntilChangedBy { it.types },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableContentTypes.sortedByOrdinal(),
                    selectedItems = selected.types,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val demographics: StateFlow<FilterProperty<Demographic>> = combine(
        filterOptions.asFlow(),
        listFilter.distinctUntilChangedBy { it.demographics },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableDemographics.sortedByOrdinal(),
                    selectedItems = selected.demographics,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val locale: StateFlow<FilterProperty<Locale?>> = combine(
        filterOptions.asFlow(),
        listFilter.distinctUntilChangedBy { it.locale },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableLocales.sortedWithSafe(LocaleComparator()).addFirstDistinct(null),
                    selectedItems = setOfNotNull(selected.locale),
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val originalLocale: StateFlow<FilterProperty<Locale?>> = if (capabilities.isOriginalLocaleSupported) {
        combine(
            filterOptions.asFlow(),
            listFilter.distinctUntilChangedBy { it.originalLocale },
        ) { available, selected ->
            available.fold(
                onSuccess = {
                    FilterProperty(
                        availableItems = it.availableLocales.sortedWithSafe(LocaleComparator()).addFirstDistinct(null),
                        selectedItems = setOfNotNull(selected.originalLocale),
                    )
                },
                onFailure = {
                    FilterProperty.error(it)
                },
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val year: StateFlow<FilterProperty<Int>> = if (capabilities.isYearSupported) {
        listFilter.distinctUntilChangedBy { it.year }.map { selected ->
            FilterProperty(
                availableItems = listOf(YEAR_MIN, MAX_YEAR),
                selectedItems = setOf(selected.year),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val yearRange: StateFlow<FilterProperty<Int>> = if (capabilities.isYearRangeSupported) {
        listFilter.distinctUntilChanged { old, new ->
            old.yearTo == new.yearTo && old.yearFrom == new.yearFrom
        }.map { selected ->
            FilterProperty(
                availableItems = listOf(YEAR_MIN, MAX_YEAR),
                selectedItems = setOf(selected.yearFrom.ifZero { YEAR_MIN }, selected.yearTo.ifZero { MAX_YEAR }),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val savedFilters: StateFlow<FilterProperty<PersistableFilter>> = combine(
        savedFiltersRepository.observeAll(repository.source),
        listFilter,
    ) { available, applied ->
        FilterProperty(
            availableItems = available,
            selectedItems = setOfNotNull(available.find { it.filter == applied }),
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.EMPTY)

    private val filterHost: FilterHost?
        get() = repository as? FilterHost

    /** True when the source exposes a dynamic [FilterList] that should use the dynamic filter UI. */
    val isDynamicFilter: Boolean
        get() = filterHost?.isDynamicFiltersSupported == true

    init {
        // Persist the active source sort (the "srt@…" tag) per source so it survives app restarts.
        if (isDynamicFilter) {
            listFilter
                .map { f -> f.tags.firstOrNull { it.key.startsWith(FilterMapper.SORT_KEY_PREFIX) } }
                .distinctUntilChanged()
                .onEach { sortTag ->
                    sourceSettings.lastSortTagKey = sortTag?.key
                    sourceSettings.lastSortTagTitle = sortTag?.title
                }
                .launchIn(coroutineScope)
            // Load the source's own default sort option name so the chip can show it instead of the
            // generic "Popular" fallback when no explicit sort has been chosen yet.
            coroutineScope.launch {
                val host = filterHost ?: return@launch
                val defaults = host.loadFilterList()
                sortLabel.value = when (val ref = FilterMapper.findSortFilter(defaults)) {
                    is FilterMapper.SortRef.OfSort -> {
                        val idx = ref.filter.state?.index ?: 0
						ref.filter.values.getOrNull(idx)
                    }
                    is FilterMapper.SortRef.OfSelect -> ref.filter.values.getOrNull(ref.filter.state)?.toString()
                    null -> null
                }
            }
        }
    }

    /** Rebuilds the last-saved sort tag (if any) so a dynamic source opens with its remembered sort. */
    private fun restoreSortFilter(): MangaListFilter {
        if (!isDynamicFilter) {
            return MangaListFilter.EMPTY
        }
        val key = sourceSettings.lastSortTagKey
        val title = sourceSettings.lastSortTagTitle
        if (key.isNullOrEmpty() || title == null || !key.startsWith(FilterMapper.SORT_KEY_PREFIX)) {
            return MangaListFilter.EMPTY
        }
        return MangaListFilter(tags = setOf(MangaTag(title = title, key = key, source = repository.source)))
    }

    fun reset() {
        listFilter.value = MangaListFilter.EMPTY
    }

	@Suppress("unused")
    fun resetSourceSort() {
        listFilter.update { f ->
            f.copy(tags = f.tags.filterNot { it.key.startsWith(FilterMapper.SORT_KEY_PREFIX) }.toSet())
        }
    }

    /**
     * Loads the source's filters: [WorkingFilters.defaults] in their default state and
     * [WorkingFilters.working] with the currently-applied filter decoded onto it. Both are fresh,
     * independently-mutable instances.
     */
    suspend fun loadWorkingFilters(): WorkingFilters {
        val host = filterHost ?: return WorkingFilters(FilterList(), FilterList())
        val defaults = host.loadFilterList()
        val working = host.loadFilterList()
        FilterMapper.decode(working, listFilter.value)
        return WorkingFilters(working = working, defaults = defaults)
    }

    /** Encodes the mutated [working] list (vs [defaults]) into the active filter, keeping the search query. */
    fun applyDynamicFilters(working: FilterList, defaults: FilterList) {
        val tags = FilterMapper.encode(working, defaults, repository.source)
        val query = listFilter.value.takeQueryIfSupported()
        listFilter.value = MangaListFilter(query = query, tags = tags)
    }

    /** Loads the current sort state for the compact sort picker (the source's own sort, or the built-in orders). */
    suspend fun loadSortState(): SortState {
        val host = filterHost
        if (host?.isDynamicFiltersSupported == true) {
            val working = host.loadFilterList()
            FilterMapper.decode(working, listFilter.value)
            when (val ref = FilterMapper.findSortFilter(working)) {
                is FilterMapper.SortRef.OfSort -> {
                    val selection = ref.filter.state
                    return SortState.Source(
                        title = ref.filter.name,
                        options = ref.filter.values.toList(),
                        selectedIndex = selection?.index ?: -1,
                        isAscending = selection?.ascending == true,
                        supportsDirection = true,
                    )
                }

                is FilterMapper.SortRef.OfSelect -> {
                    return SortState.Source(
                        title = ref.filter.name,
                        options = ref.filter.values.map { it.toString() },
                        selectedIndex = ref.filter.state,
                        isAscending = false,
                        supportsDirection = false,
                    )
                }

                null -> Unit
            }
        }
        return SortState.Native(
            options = availableSortOrders.sortedByOrdinal(),
            selected = sourceOrder.value,
        )
    }

    /** Applies a source sort selection ([Filter.Sort] or sort [Filter.Select]), preserving other filters. */
    fun applySourceSort(index: Int, isAscending: Boolean) = coroutineScope.launch {
        val host = filterHost ?: return@launch
        val defaults = host.loadFilterList()
        val working = host.loadFilterList()
        FilterMapper.decode(working, listFilter.value)
        when (val ref = FilterMapper.findSortFilter(working)) {
            is FilterMapper.SortRef.OfSort -> ref.filter.state = Filter.Sort.Selection(index, isAscending)
            is FilterMapper.SortRef.OfSelect -> if (index in ref.filter.values.indices) {
                ref.filter.state = index
            }

            null -> return@launch
        }
        applyDynamicFilters(working, defaults)
    }

    fun snapshot() = Snapshot(
        sortOrder = sourceOrder.value,
        listFilter = listFilter.value,
        sortLabel = sortLabel.value,
    )

    fun observe(): Flow<Snapshot> = combine(sourceOrder, listFilter, sortLabel) { o, f, l ->
        Snapshot(o, f, l)
    }

    fun setSortOrder(newSortOrder: SortOrder) {
        sourceOrder.value = newSortOrder
        repository.defaultSortOrder = newSortOrder
    }

    fun set(value: MangaListFilter) {
        listFilter.value = value
    }

    fun setAdjusted(value: MangaListFilter) {
        var newFilter = value
        if (!newFilter.author.isNullOrEmpty() && !capabilities.isAuthorSearchSupported) {
            newFilter = newFilter.copy(
                query = newFilter.author,
                author = null,
            )
        }
        if (!newFilter.query.isNullOrEmpty() && !newFilter.hasNonSearchOptions() && !capabilities.isSearchWithFiltersSupported) {
            newFilter = MangaListFilter(query = newFilter.query)
        }
        set(newFilter)
    }

    fun saveCurrentFilter(name: String) = coroutineScope.launch {
        savedFiltersRepository.save(repository.source, name, listFilter.value)
    }

    fun renameSavedFilter(id: Int, newName: String) = coroutineScope.launch {
        savedFiltersRepository.rename(repository.source, id, newName)
    }

    fun deleteSavedFilter(id: Int) = coroutineScope.launch {
        savedFiltersRepository.delete(repository.source, id)
    }

    fun setQuery(value: String?) {
        val newQuery = value?.trim()?.nullIfEmpty()
        listFilter.update { oldValue ->
            if (capabilities.isSearchWithFiltersSupported || newQuery == null) {
                oldValue.copy(query = newQuery)
            } else MangaListFilter(query = newQuery)
        }
    }

    fun setLocale(value: Locale?) {
        listFilter.update { oldValue ->
            oldValue.copy(
                locale = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setAuthor(value: String?) {
        listFilter.update { oldValue ->
            oldValue.copy(
                author = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setOriginalLocale(value: Locale?) {
        listFilter.update { oldValue ->
            oldValue.copy(
                originalLocale = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setYear(value: Int) {
        listFilter.update { oldValue ->
            oldValue.copy(
                year = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setYearRange(valueFrom: Int, valueTo: Int) {
        listFilter.update { oldValue ->
            oldValue.copy(
                yearFrom = valueFrom,
                yearTo = valueTo,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleState(value: MangaState, isSelected: Boolean) {
        listFilter.update { oldValue ->
            oldValue.copy(
                states = if (isSelected) oldValue.states + value else oldValue.states - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleContentRating(value: ContentRating, isSelected: Boolean) {
        listFilter.update { oldValue ->
            oldValue.copy(
                contentRating = if (isSelected) oldValue.contentRating + value else oldValue.contentRating - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleDemographic(value: Demographic, isSelected: Boolean) {
        listFilter.update { oldValue ->
            oldValue.copy(
                demographics = if (isSelected) oldValue.demographics + value else oldValue.demographics - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleContentType(value: ContentType, isSelected: Boolean) {
        listFilter.update { oldValue ->
            oldValue.copy(
                types = if (isSelected) oldValue.types + value else oldValue.types - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleTag(value: MangaTag, isSelected: Boolean) {
        listFilter.update { oldValue ->
            val newTags = if (capabilities.isMultipleTagsSupported) {
                if (isSelected) oldValue.tags + value else oldValue.tags - value
            } else if (isSelected) setOf(value) else emptySet()
            oldValue.copy(
                tags = newTags,
                tagsExclude = oldValue.tagsExclude - newTags,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleTagExclude(value: MangaTag, isSelected: Boolean) {
        listFilter.update { oldValue ->
            val newTagsExclude = if (capabilities.isMultipleTagsSupported) {
                if (isSelected) oldValue.tagsExclude + value else oldValue.tagsExclude - value
            } else if (isSelected) setOf(value) else emptySet()
            oldValue.copy(
                tags = oldValue.tags - newTagsExclude,
                tagsExclude = newTagsExclude,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun getAllTags(): Flow<Result<List<MangaTag>>> = filterOptions.asFlow().map {
        it.map { x -> x.availableTags.sortedWithSafe(TagTitleComparator(sourceLocale)) }
    }

    private fun MangaListFilter.takeQueryIfSupported() = when {
        capabilities.isSearchWithFiltersSupported -> query
        query.isNullOrEmpty() -> query
        hasNonSearchOptions() -> null
        else -> query
    }

    private fun getTopTags(limit: Int): Flow<Result<List<MangaTag>>> = combine(
        flow { emit(searchRepository.getTopTags(repository.source, limit)) }, filterOptions.asFlow(),
    ) { suggested, options ->
        val all = options.getOrNull()?.availableTags.orEmpty()
        val result = ArrayList<MangaTag>(limit)
        result.addAll(suggested.take(limit))
        if (result.size < limit) result.addAll(all.shuffled().take(limit - result.size))
        if (result.isNotEmpty()) Result.success(result) else { options.map { result } }
    }.catch { emit(Result.failure(it)) }

    private fun getBottomTags(limit: Int): Flow<Result<List<MangaTag>>> = combine(
        flow { emit(searchRepository.getRareTags(repository.source, limit)) }, filterOptions.asFlow(),
    ) { suggested, options ->
        val all = options.getOrNull()?.availableTags.orEmpty()
        val result = ArrayList<MangaTag>(limit)
        result.addAll(suggested.take(limit))
        if (result.size < limit) result.addAll(all.shuffled().take(limit - result.size))
        if (result.isNotEmpty()) Result.success(result) else { options.map { result } }
    }.catch { emit(Result.failure(it)) }

    private fun <T> List<T>.addFirstDistinct(other: Collection<T>): List<T> {
        val result = ArrayDeque<T>(this.size + other.size)
        result.addAll(this)
        for (item in other) {
            if (item !in result) { result.addFirst(item) }
        }
        return result
    }

    private fun <T> List<T>.addFirstDistinct(item: T): List<T> {
        val result = ArrayDeque<T>(this.size + 1)
        result.addAll(this)
        if (item !in result) result.addFirst(item)
        return result
    }

    data class Snapshot(
        val sortOrder: SortOrder,
        val listFilter: MangaListFilter,
        val sortLabel: String? = null,
    )

    /** A fresh pair of filter lists: the user-editable [working] copy and the [defaults] baseline. */
    class WorkingFilters(
        val working: FilterList,
        val defaults: FilterList,
    )

    /** Sort options surfaced by the compact sort picker. */
    sealed interface SortState {

        data class Source(
            val title: String,
            val options: List<String>,
            val selectedIndex: Int,
            val isAscending: Boolean,
            val supportsDirection: Boolean,
        ) : SortState

        data class Native(
            val options: List<SortOrder>,
            val selected: SortOrder,
        ) : SortState
    }

    interface Owner {

        val filterCoordinator: FilterCoordinator
    }

    companion object {

        private const val TAGS_LIMIT = 12
        private val MAX_YEAR = Calendar.getInstance()[Calendar.YEAR] + 1

        fun find(fragment: Fragment): FilterCoordinator? {
            (fragment.activity as? Owner)?.let {
                return it.filterCoordinator
            }
            var f = fragment
            while (true) {
                (f as? Owner)?.let {
                    return it.filterCoordinator
                }
                f = f.parentFragment ?: break
            }
            return null
        }

        fun require(fragment: Fragment): FilterCoordinator {
            return find(fragment) ?: throw IllegalStateException("FilterCoordinator cannot be found")
        }
    }
}
