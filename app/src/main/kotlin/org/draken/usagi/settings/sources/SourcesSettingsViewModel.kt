package org.draken.usagi.settings.sources

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.tsukimix.core.parser.external.ExtensionProvider
import org.draken.tsukimix.core.parser.external.NativeExtManager
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
		@param:ApplicationContext private val context: Context,
	) : BaseViewModel() {
		private val linksHandlerActivity = ComponentName(context, "org.draken.usagi.details.ui.DetailsByLinkActivity")

		val sourceCounts =
			sourcesRepository
				.observeManageableSourcesCount()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0 to 0)
		val externalPluginCount = MutableStateFlow(calcExternalCount())
		val availableSourcesCount =
			sourcesRepository
				.observeAvailableSourcesCount()
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, -1)
		val isLinksEnabled = MutableStateFlow(isLinksEnabled())

		init {
			refreshExternalCounts()
		}

		private fun calcExternalCount(): Int {
			val cached = catalogProvider.getSavedRepositories().map { catalogProvider.canonicalKey(it) }
			val inst = directManager.installed.value.map { catalogProvider.canonicalKey(it.repositoryUrl) }
			return (cached + inst).filter { it.isNotBlank() }.toSet().size
		}

		fun refreshExternalCounts() {
			launchJob(Dispatchers.Default) {
				val arts = catalogProvider.loadSavedCached()
				val artKeys = arts.map { catalogProvider.canonicalKey(it.repositoryUrl) }
				val instKeys =
					directManager.installed.value.map {
						catalogProvider.canonicalKey(it.repositoryUrl)
					}
				val repos = (artKeys + instKeys).filter { it.isNotBlank() }.toSet()
				externalPluginCount.value = repos.size
			}
		}

		fun setLinksEnabled(isEnabled: Boolean) {
			context.packageManager.setComponentEnabledSetting(
				linksHandlerActivity,
				if (isEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP,
			)
			isLinksEnabled.value = isEnabled
		}

		private fun isLinksEnabled(): Boolean =
			context.packageManager.getComponentEnabledSetting(linksHandlerActivity) ==
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED
	}
