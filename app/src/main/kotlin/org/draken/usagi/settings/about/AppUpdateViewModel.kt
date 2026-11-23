package org.draken.usagi.settings.about

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import org.draken.usagi.core.github.AppUpdateRepository
import org.draken.usagi.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel
	@Inject
	constructor(
		private val repository: AppUpdateRepository,
	) : BaseViewModel() {
		val nextVersion = repository.observeAvailableUpdate()

		init {
			if (nextVersion.value == null) {
				launchLoadingJob(Dispatchers.Default) {
					repository.fetchUpdate(true)
				}
			}
		}
	}
