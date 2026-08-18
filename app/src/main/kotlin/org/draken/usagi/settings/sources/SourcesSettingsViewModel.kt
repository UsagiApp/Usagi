package org.draken.usagi.settings.sources

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.tsukimix.core.parser.tachiyomi.ExtensionProvider
import org.draken.tsukimix.core.parser.tachiyomi.NativeExtManager
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.explore.data.MangaSourcesRepository
import javax.inject.Inject

@HiltViewModel
class SourcesSettingsViewModel
	@Inject
	constructor(
		sourcesRepository: MangaSourcesRepository,
		private val catalogProvider: ExtensionProvider,
		private val directManager: NativeExtManager,
		@ApplicationContext private val context: Context,
	) : BaseViewModel() {
		private val linksHandlerActivity = ComponentName(context, "org.draken.usagi.details.ui.DetailsByLinkActivity")

		val sourceCounts =
			sourcesRepository
				.observeManageableSourcesCount()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0 to 0)

		private val externalPluginCountState = MutableStateFlow(calculateExternalCount())
		val externalPluginCount = externalPluginCountState

		val availableSourcesCount =
			sourcesRepository
				.observeAvailableSourcesCount()
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)

		val isLinksEnabled = MutableStateFlow(isLinksEnabled())

		init {
			refreshExternalCounts()
		}

		private fun calculateExternalCount(): Int {
			val cached = catalogProvider.getSavedRepositories().map { catalogProvider.canonicalKey(it) }
			val installed = directManager.installed.value.map { catalogProvider.canonicalKey(it.repositoryUrl) }
			return (cached + installed).filter { it.isNotBlank() }.toSet().size
		}

		fun refreshExternalCounts() {
			launchJob(Dispatchers.Default) {
				val artifacts = catalogProvider.loadSavedCached()
				val repos =
					(
						artifacts.map { catalogProvider.canonicalKey(it.repositoryUrl) } +
							directManager.installed.value.map { catalogProvider.canonicalKey(it.repositoryUrl) }
					).filter { it.isNotBlank() }
						.toSet()
				externalPluginCountState.value = repos.size
			}
		}

		fun setLinksEnabled(isEnabled: Boolean) {
			context.packageManager.setComponentEnabledSetting(
				linksHandlerActivity,
				if (isEnabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP,
			)
			isLinksEnabled.value = isLinksEnabled()
		}

		private fun isLinksEnabled(): Boolean {
			val state = context.packageManager.getComponentEnabledSetting(linksHandlerActivity)
			return state == COMPONENT_ENABLED_STATE_ENABLED || state == COMPONENT_ENABLED_STATE_DEFAULT
		}
	}
