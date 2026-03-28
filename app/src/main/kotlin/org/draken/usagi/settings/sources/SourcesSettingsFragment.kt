package org.draken.usagi.settings.sources

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.TriStateOption
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.dialog.setEditText
import org.draken.usagi.core.util.ext.getQuantityStringSafe
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.setDefaultValueCompat
import org.draken.usagi.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.parsers.util.names
import kotlin.coroutines.resume

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
		updatePluginsList()
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

		"import_jar" -> {
			importJarLauncher.launch(arrayOf(
				"application/java-archive",
				"application/vnd.android.package-archive",
				"application/octet-stream",
			))
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

	private fun updatePluginsList() {
		val category = findPreference<PreferenceCategory>("plugins_category") ?: return
		category.removeAll()

		val plugins = DynamicParserManager.getInstalledPlugins(requireContext())
		if (plugins.isEmpty()) {
			category.addPreference(Preference(requireContext()).apply {
				title = context.getString(R.string.no_plugins)
				summary = context.getString(R.string.no_plugins_summary)
				isSelectable = false
			})
		} else {
			plugins.forEach { pluginName ->
				category.addPreference(Preference(requireContext()).apply {
					title = pluginName
					summary = context.getString(R.string.tap_to_delete_plugin)
					setOnPreferenceClickListener {
						buildAlertDialog(requireContext()) {
							setTitle(R.string.delete_plugin)
							setMessage(context.getString(R.string.confirm_delete_plugin, pluginName))
							setNegativeButton(android.R.string.cancel, null)
							setPositiveButton(R.string.delete) { _, _ ->
								lifecycleScope.launch(Dispatchers.IO) {
									DynamicParserManager.deletePlugin(requireContext(), pluginName)
									withContext(Dispatchers.Main) {
										updatePluginsList()
										Toast.makeText(
											requireContext(),
											context.getString(R.string.deleted_plugin, pluginName),
											Toast.LENGTH_SHORT,
										).show()
									}
								}
							}
						}.show()
						true
					}
				})
			}
		}
	}

	private fun importJar(uri: android.net.Uri) {
		val context = requireContext()
		lifecycleScope.launch {
			try {
				val originalName = DocumentFile.fromSingleUri(context, uri)?.name
					?: "plugin_${System.currentTimeMillis()}.jar"
				val pluginsDir = java.io.File(context.filesDir, "plugins")
				if (!pluginsDir.exists()) pluginsDir.mkdirs()

				val dialogResult = suspendCancellableCoroutine { cont ->
					lateinit var editText: android.widget.EditText
					val dialog = buildAlertDialog(context) {
						editText = setEditText(InputType.TYPE_CLASS_TEXT, singleLine = true)
						editText.setText(originalName.removeSuffix(".jar"))
						editText.hint = context.getString(R.string.plugin_name)
						setTitle(R.string.set_plugin_name)
						setNegativeButton(android.R.string.cancel) { _, _ ->
							if (cont.isActive) cont.resume(null)
						}
						setPositiveButton(android.R.string.ok) { _, _ ->
							if (cont.isActive) cont.resume(editText.text.toString().trim())
						}
					}
					dialog.setOnCancelListener {
						if (cont.isActive) cont.resume(null)
					}
					dialog.show()
				}
				if (dialogResult.isNullOrBlank()) return@launch

				val fileName = "$dialogResult.jar"
				val outFile = java.io.File(pluginsDir, fileName)

				withContext(Dispatchers.IO) {
					context.contentResolver.openInputStream(uri)?.use { input ->
						java.io.FileOutputStream(outFile).use { output ->
							input.copyTo(output)
						}
					}
				}
				DynamicParserManager.loadParsersFromDirectory(context, pluginsDir)
				updatePluginsList()
				Toast.makeText(context, R.string.load_success, Toast.LENGTH_LONG).show()
			} catch (_: Exception) {
				Toast.makeText(context, R.string.load_failed, Toast.LENGTH_LONG).show()
			}
		}
	}
}
