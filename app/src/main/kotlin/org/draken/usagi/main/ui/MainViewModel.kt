package org.draken.usagi.main.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.exceptions.EmptyHistoryException
import org.draken.usagi.core.github.AppUpdateRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.prefs.observeAsStateFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.main.domain.ReadingResumeEnabledUseCase
import tsuki.model.Manga
import org.draken.usagi.tracker.domain.TrackingRepository
import org.draken.usagi.settings.sources.manage.plugins.UpdatePluginsProvider
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val appUpdateRepository: AppUpdateRepository,
	trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val updatePluginsProvider: UpdatePluginsProvider,
	readingResumeEnabledUseCase: ReadingResumeEnabledUseCase,
) : BaseViewModel() {

	var isUpdateDialogShown = false // alway shows at startup

	val onOpenReader = MutableEventFlow<Manga>()
	val onFirstStart = MutableEventFlow<Unit>()

	val isResumeEnabled = readingResumeEnabledUseCase()
		.withErrorHandling()
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = false,
		)

	val appUpdate = appUpdateRepository.observeAvailableUpdate()

	val feedCounter = trackingRepository.observeUnreadUpdatesCount()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, 0)

	val isBottomNavPinned = settings.observeAsFlow(
		AppSettings.KEY_NAV_PINNED,
	) {
		isNavBarPinned
	}.flowOn(Dispatchers.Default)

	val isFloatingNav = settings.observeAsFlow(
		AppSettings.KEY_NAV_FLOATING,
	) {
		isFloatingNav
	}.flowOn(Dispatchers.Default)

	val isIncognitoModeEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_INCOGNITO_MODE,
		valueProducer = { isIncognitoModeEnabled },
	)

	init {
		launchJob {
			if (settings.isCheckAppUpdateEnabled) {
				appUpdateRepository.fetchUpdate()
			}
		}
		launchJob {
			if (settings.isFirstLaunch) {
				settings.isFirstLaunch = false
				settings.isCheckAppUpdateEnabled = false
				onFirstStart.call(Unit)
			}
		}
	}

	fun openLastReader() {
		launchLoadingJob(Dispatchers.Default) {
			val manga = historyRepository.getLastOrNull() ?: throw EmptyHistoryException()
			onOpenReader.call(manga)
		}
	}

	fun setIncognitoMode(isEnabled: Boolean) {
		settings.isIncognitoModeEnabled = isEnabled
	}

	fun runAutoUpdate() {
		if (settings.isAutoPluginsEnabled) {
			launchJob(Dispatchers.Default) {
				updatePluginsProvider.runAutoUpdate(settings)
			}
		}
	}
}
