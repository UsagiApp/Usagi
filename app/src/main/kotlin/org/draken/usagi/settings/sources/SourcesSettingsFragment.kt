package org.draken.usagi.settings.sources

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.TriStateOption
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.core.util.ext.getQuantityStringSafe
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.setDefaultValueCompat
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.explore.data.SourcesSortOrder
import org.draken.usagi.settings.utils.MultiAutoCompleteTextViewPreference
import tsuki.util.names
import javax.inject.Inject

@AndroidEntryPoint
class SourcesSettingsFragment :
	BasePreferenceFragment(R.string.remote_sources),
	SharedPreferences.OnSharedPreferenceChangeListener {
	private val viewModel by viewModels<SourcesSettingsViewModel>()

	@Inject
	lateinit var mangaDynamicRepository: MangaDynamicRepository

	@Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	override fun onCreatePreferences(
		savedInstanceState: Bundle?,
		rootKey: String?,
	) {
		addPreferencesFromResource(R.xml.pref_sources)
		findPreference<ListPreference>(AppSettings.KEY_SOURCES_ORDER)?.run {
			entryValues = SourcesSortOrder.entries.names()
			entries = SourcesSortOrder.entries.map { context.getString(it.titleResId) }.toTypedArray()
			setDefaultValueCompat(SourcesSortOrder.MANUAL.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_INCOGNITO_NSFW)?.run {
			entryValues = TriStateOption.entries.names()
			setDefaultValueCompat(TriStateOption.ASK.name)
		}
		findPreference<MultiAutoCompleteTextViewPreference>(AppSettings.KEY_FIX_EXCLUDE_SOURCES)?.run {
			autoCompleteProvider =
				object : MultiAutoCompleteTextViewPreference.AutoCompleteProvider {
					override suspend fun getSuggestions(query: String): List<String> =
						sourcesRepository.allMangaSources
							.map { it.getTitle(context) }
							.filter { it.contains(query, true) }
				}
			summaryProvider =
				MultiAutoCompleteTextViewPreference.SimpleSummaryProvider(
					getString(R.string.fix_excluded_sources_summary),
				)
		}
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.summary =
			getString(R.string.sources_count_summary, viewModel.totalSourcesCount, viewModel.externalSourcesCount)

		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.let { pref ->
			viewModel.availableSourcesCount.observe(viewLifecycleOwner) {
				updateCatalogSummary(pref)
			}
		}

		findPreference<TwoStatePreference>(AppSettings.KEY_HANDLE_LINKS)?.let { pref ->
			viewModel.isLinksEnabled.observe(viewLifecycleOwner) {
				pref.isChecked = it
			}
		}
		updateEnableAllDependencies()
		viewModel.externalPluginCount.observe(viewLifecycleOwner) { updatePluginsSummary() }
		viewModel.externalCatalogSourceCount.observe(viewLifecycleOwner) {
			findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.let(::updateCatalogSummary)
			hideEmptyCatalog()
		}
		updatePluginsSummary()
		settings.subscribe(this)
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshExternalCounts()
		updatePluginsSummary()
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean =
		when (preference.key) {
			AppSettings.KEY_SOURCES_CATALOG -> {
				router.openSourcesCatalog()
				true
			}

			AppSettings.KEY_HANDLE_LINKS -> {
				viewModel.setLinksEnabled((preference as TwoStatePreference).isChecked)
				true
			}

			else -> {
				super.onPreferenceTreeClick(preference)
			}
		}

	override fun onSharedPreferenceChanged(
		sharedPreferences: SharedPreferences?,
		key: String?,
	) {
		when (key) {
			AppSettings.KEY_SOURCES_ENABLED_ALL -> updateEnableAllDependencies()
		}
	}

	private fun updateEnableAllDependencies() {
		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.isEnabled = !settings.isAllSourcesEnabled
	}

	private fun updatePluginsSummary() {
		val count = mangaDynamicRepository.get().size + viewModel.externalPluginCount.value
		findPreference<Preference>("plugins_manager")?.summary =
			resources.getQuantityStringSafe(R.plurals.items, count, count)
		hideEmptyCatalog()
	}

	private fun updateCatalogSummary(pref: Preference) {
		val count = viewModel.availableSourcesCount.value
		val external = viewModel.externalCatalogSourceCount.value
		pref.summary =
			when {
				count == 0 -> getString(R.string.all_sources_enabled)
				count > 0 && external > 0 -> getString(R.string.available_sources_summary, count, external)
				count > 0 -> getString(R.string.available_d, count)
				else -> null
			}
		hideEmptyCatalog()
	}

	private fun hideEmptyCatalog() {
		val catalog = viewModel.availableSourcesCount.value
		val imported = mangaDynamicRepository.get().size + viewModel.externalPluginCount.value

		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.isVisible = !(catalog == 0 && imported == 0)
	}
}
