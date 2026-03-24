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
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.TriStateOption
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.core.util.ext.getQuantityStringSafe
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.setDefaultValueCompat
import org.draken.usagi.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.parsers.util.names

import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SourcesSettingsFragment : BasePreferenceFragment(R.string.remote_sources),
	SharedPreferences.OnSharedPreferenceChangeListener {

	private val viewModel by viewModels<SourcesSettingsViewModel>()

	private val importJarLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			importJar(uri)
		}
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.let { pref ->
			viewModel.enabledSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = if (it >= 0) {
					resources.getQuantityStringSafe(R.plurals.items, it, it)
				} else {
					null
				}
			}
		}
		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.let { pref ->
			viewModel.availableSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = when {
					it == 0 -> getString(R.string.all_sources_enabled)
					it > 0 -> getString(R.string.available_d, it)
					else -> null
				}
			}
		}
		findPreference<TwoStatePreference>(AppSettings.KEY_HANDLE_LINKS)?.let { pref ->
			viewModel.isLinksEnabled.observe(viewLifecycleOwner) {
				pref.isChecked = it
			}
		}
		updateEnableAllDependencies()
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		AppSettings.KEY_SOURCES_CATALOG -> {
			router.openSourcesCatalog()
			true
		}

		"import_parser_jar" -> {
			importJarLauncher.launch(arrayOf("application/java-archive", "application/vnd.android.package-archive", "application/octet-stream"))
			true
		}

		AppSettings.KEY_HANDLE_LINKS -> {
			viewModel.setLinksEnabled((preference as TwoStatePreference).isChecked)
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_SOURCES_ENABLED_ALL -> updateEnableAllDependencies()
		}
	}

	private fun updateEnableAllDependencies() {
		findPreference<Preference>(AppSettings.KEY_SOURCES_CATALOG)?.isEnabled = !settings.isAllSourcesEnabled
	}

	private fun importJar(uri: android.net.Uri) {
		val context = requireContext()
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.IO) {
					context.contentResolver.openInputStream(uri)?.use { input ->
						val outFile = java.io.File(context.filesDir, "parsers-plugin.jar")
						java.io.FileOutputStream(outFile).use { output ->
							input.copyTo(output)
						}
					}
				}
				org.draken.usagi.core.parser.DynamicParserManager.loadParsersFromJar(
					context, java.io.File(context.filesDir, "parsers-plugin.jar")
				)
				Toast.makeText(context, R.string.load_success, Toast.LENGTH_LONG).show()
			} catch (_: Exception) {
				Toast.makeText(context, R.string.load_failed, Toast.LENGTH_LONG).show()
			}
		}
	}
}
