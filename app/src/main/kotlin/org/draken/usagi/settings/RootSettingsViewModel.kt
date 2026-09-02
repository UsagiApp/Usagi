package org.draken.usagi.settings

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.explore.data.MangaSourcesRepository
import javax.inject.Inject

@HiltViewModel
class RootSettingsViewModel
	@Inject
	constructor(
		sourcesRepository: MangaSourcesRepository,
	) : BaseViewModel() {
		val sourceCounts =
			sourcesRepository
				.observeManageableSourcesCount()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0 to 0)
	}
