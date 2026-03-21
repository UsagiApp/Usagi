package org.draken.usagi.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.prefs.observeAsStateFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.model.DateTimeAgo
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.calculateTimeAgo
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.MangaListMapper
import org.draken.usagi.list.domain.QuickFilterListener
import org.draken.usagi.list.ui.model.EmptyState
import org.draken.usagi.list.ui.model.ListHeader
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import org.draken.usagi.list.ui.model.toErrorState
import org.draken.usagi.tracker.domain.TrackingRepository
import org.draken.usagi.tracker.domain.UpdatesListQuickFilter
import org.draken.usagi.tracker.domain.model.TrackingLogItem
import org.draken.usagi.tracker.ui.feed.model.FeedItem
import org.draken.usagi.tracker.ui.feed.model.UpdatedMangaHeader
import org.draken.usagi.tracker.work.TrackWorker
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val mangaListMapper: MangaListMapper,
	private val quickFilter: UpdatesListQuickFilter,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isReady = AtomicBoolean(false)

	val isRunning = scheduler.observeIsRunning()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val isHeaderEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_FEED_HEADER,
		valueProducer = { isFeedHeaderVisible },
	)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	@Suppress("USELESS_CAST")
	val content = combine(
		observeHeader(),
		quickFilter.appliedOptions,
		combine(limit, quickFilter.appliedOptions.combineWithSettings(), ::Pair)
			.flatMapLatest { repository.observeTrackingLog(it.first, it.second) },
	) { header, filters, list ->
		val result = ArrayList<ListModel>((list.size * 1.4).toInt().coerceAtLeast(3))
		quickFilter.filterItem(filters)?.let(result::add)
		if (header != null) {
			result += header
		}
		if (list.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_empty_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			isReady.set(true)
			list.mapListTo(result)
		}
		result as List<ListModel>
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
		}
	}

	fun clearFeed(clearCounters: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearLogs()
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	fun update() {
		scheduler.startNow()
	}

	fun setHeaderEnabled(value: Boolean) {
		settings.isFeedHeaderVisible = value
	}

	fun onItemClick(item: FeedItem) {
		launchJob(Dispatchers.Default, CoroutineStart.ATOMIC) {
			repository.markAsRead(item.id)
		}
	}

	private suspend fun List<TrackingLogItem>.mapListTo(destination: MutableList<ListModel>) {
		var prevDate: DateTimeAgo? = null
		for (item in this) {
			val date = calculateTimeAgo(item.createdAt)
			if (prevDate != date) {
				destination += if (date != null) {
					ListHeader(date)
				} else {
					ListHeader(R.string.unknown)
				}
			}
			prevDate = date
			destination += mangaListMapper.toFeedItem(item)
		}
	}

	private fun observeHeader() = isHeaderEnabled.flatMapLatest { hasHeader ->
		if (hasHeader) {
			quickFilter.appliedOptions.combineWithSettings().flatMapLatest {
				repository.observeUpdatedManga(10, it)
			}.map { mangaList ->
				if (mangaList.isEmpty()) {
					null
				} else {
					UpdatedMangaHeader(
						mangaList.map { mangaListMapper.toListModel(it.manga, ListMode.GRID) },
					)
				}
			}
		} else {
			flowOf(null)
		}
	}

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}
}
