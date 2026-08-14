package org.draken.usagi.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import org.draken.usagi.core.model.isExternalSource
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.explore.data.MangaSourcesRepository
import javax.inject.Inject

@HiltViewModel
class RootSettingsViewModel
	@Inject
	constructor(
		private val sourcesRepository: MangaSourcesRepository,
	) : BaseViewModel() {
		val totalSourcesCount: Int
			get() = sourcesRepository.allMangaSources.size

		val externalSourcesCount: Int
			get() = sourcesRepository.allMangaSources.count { it.isExternalSource() }
	}
