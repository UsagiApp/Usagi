package org.draken.usagi.history.domain

import org.draken.usagi.core.os.NetworkState
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.MangaListQuickFilter
import javax.inject.Inject

class HistoryListQuickFilter @Inject constructor(
	private val settings: AppSettings,
	private val repository: HistoryRepository,
	networkState: NetworkState,
) : MangaListQuickFilter(settings) {

	init {
		setFilterOption(ListFilterOption.Downloaded, !networkState.value)
	}

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
		add(ListFilterOption.Downloaded)
		if (settings.isTrackerEnabled) {
			add(ListFilterOption.Macro.NEW_CHAPTERS)
		}
		add(ListFilterOption.Macro.COMPLETED)
		add(ListFilterOption.Macro.FAVORITE)
		add(ListFilterOption.NOT_FAVORITE)
		if (!settings.isNsfwContentDisabled) {
			add(ListFilterOption.Macro.NSFW)
		}
		repository.getPopularTags(5).mapTo(this) {
			ListFilterOption.Tag(it)
		}
		repository.getPopularSources(4).mapTo(this) {
			ListFilterOption.Source(it)
		}
	}
}
