package org.draken.usagi.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.draken.usagi.core.os.NetworkState
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.MangaListQuickFilter

class FavoritesListQuickFilter
	@AssistedInject
	constructor(
		@Assisted private val categoryId: Long,
		private val settings: AppSettings,
		private val repository: FavouritesRepository,
		networkState: NetworkState,
	) : MangaListQuickFilter(settings) {
		init {
			setFilterOption(ListFilterOption.Downloaded, !networkState.value)
		}

		suspend fun availableOptions(rules: SmartFolderRules?): List<ListFilterOption> = availableOptions().filterNot { option -> rules != null && option.isCoveredBy(rules) }

		override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
			buildList {
				add(ListFilterOption.Downloaded)
				add(ListFilterOption.SFW)
				add(ListFilterOption.Macro.NSFW)
				if (settings.isTrackerEnabled) {
					add(ListFilterOption.Macro.NEW_CHAPTERS)
				}
				repository.findPopularSources(categoryId, 10).mapTo(this) {
					ListFilterOption.Source(it)
				}
				repository.findPopularTags(20).mapTo(this) {
					ListFilterOption.Tag(it)
				}
			}

		@AssistedFactory
		interface Factory {
			fun create(categoryId: Long): FavoritesListQuickFilter
		}

		private fun ListFilterOption.isCoveredBy(rules: SmartFolderRules): Boolean =
			when (this) {
				ListFilterOption.Downloaded -> rules.device != SmartFolderDevice.ANY

				ListFilterOption.SFW,
				ListFilterOption.Macro.NSFW,
				-> rules.content != SmartFolderContent.ANY

				is ListFilterOption.Source -> rules.sources.isNotEmpty()

				is ListFilterOption.Tag -> rules.tagIds.isNotEmpty()

				else -> false
			}
	}
