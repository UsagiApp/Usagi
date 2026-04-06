package org.draken.usagi.main.ui.welcome

import dagger.hilt.android.lifecycle.HiltViewModel
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.explore.data.MangaSourcesRepository
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
	private val repository: MangaSourcesRepository,
) : BaseViewModel() {

	init {
		// Mark sources badge as seen so the "new sources" badge
		// doesn't appear right after the welcome screen is dismissed
		launchJob {
			repository.clearNewSourcesBadge()
		}
	}
}
