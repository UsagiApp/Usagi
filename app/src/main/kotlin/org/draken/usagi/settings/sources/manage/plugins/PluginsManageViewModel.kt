package org.draken.usagi.settings.sources.manage.plugins

import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import tsuki.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PluginsManageViewModel @Inject constructor(
	@param:ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val savedFiltersRepository: SavedFiltersRepository,
	private val updatePluginsProvider: UpdatePluginsProvider,
	private val settings: AppSettings,
	private val mangaDynamicRepository: MangaDynamicRepository,
	private val pluginKeyResolver: PluginKeyResolver,
) : BaseViewModel() {

	val content = MutableStateFlow<List<PluginManageItem>>(emptyList())
	val selectedPlugins = MutableStateFlow<Set<String>>(emptySet())

	@Volatile
	private var pluginsSnapshot = emptyList<PluginManageItem.Plugin>()

	@Volatile
	private var query = ""

	init {
		refresh()
	}

	fun refresh() {
		launchLoadingJob(Dispatchers.Default) {
			val localPlugins = loadPluginsLocal()
			pluginsSnapshot = localPlugins
			publishFiltered()

			if (localPlugins.isNotEmpty()) {
				val updatedPlugins = coroutineScope {
					localPlugins.map { plugin ->
						async {
							val repo = plugin.repository ?: return@async plugin
							val latest = updatePluginsProvider.requestTag(repo) ?: return@async plugin
							plugin.copy(latestTag = latest)
						}
					}.awaitAll()
				}
				pluginsSnapshot = updatedPlugins
				publishFiltered()
			}
		}
	}

	fun setQuery(value: String?) {
		query = value?.trim().orEmpty()
		publishFiltered()
	}

	fun runAutoUpdate() {
		if (settings.isAutoPluginsEnabled) {
			launchJob(Dispatchers.Default) {
				updatePluginsProvider.runAutoUpdate(settings)
			}
		}
	}

	suspend fun resolveRelease(input: String, name: String? = null): ExternalPluginDto? = withContext(Dispatchers.Default) {
		val repository = updatePluginsProvider.resolve(input) ?: return@withContext null
		updatePluginsProvider.requestRelease(repository, name)
	}

	suspend fun resolveGithubReleases(input: String): List<ExternalPluginDto> = withContext(Dispatchers.Default) {
		val repository = updatePluginsProvider.resolve(input) ?: return@withContext emptyList()
		val tag = updatePluginsProvider.requestTag(repository) ?: return@withContext emptyList()
		updatePluginsProvider.requestPlugins(repository, tag)
	}

	suspend fun importFromUri(uri: Uri, fileName: String): Boolean = withContext(Dispatchers.Default) {
		val safeName = PluginFileLoader.resolve(fileName)
		runCatchingCancellable {
			val pluginsDir = mangaDynamicRepository.getDir()
			PluginFileLoader.copyFromUri(context, uri, File(pluginsDir, safeName))
			updatePluginsProvider.clearDto(safeName)
			reloadPlugins(pluginsDir)
		}.isSuccess
	}.also { if (it) refresh() }

	suspend fun importFromGithub(release: ExternalPluginDto, fileName: String = release.fileName): Boolean =
		updatePluginsProvider.installPlugin(release, PluginFileLoader.resolve(fileName))
			.also { if (it) refresh() }

	fun importPlugin(
		uri: Uri,
		getOriginalName: (Uri) -> String?,
		askName: suspend (String) -> String?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit
	) {
		launchJob(Dispatchers.Default) {
			val originalName = getOriginalName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
			val pluginName = askName(originalName.removeSuffix(".jar"))?.trim().orEmpty()
			if (pluginName.isBlank()) return@launchJob

			val fileName = PluginFileLoader.resolve(pluginName)
			if (isInstalled(fileName) && !askOverwrite(fileName)) return@launchJob

			val success = importFromUri(uri, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	fun import(askInput: suspend () -> String?, askSelect: suspend (List<String>) -> Int?,
		askOverwrite: suspend (String) -> Boolean, onResult: (Boolean) -> Unit,
	) {
		launchJob(Dispatchers.Default) {
			val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@launchJob
			val releases = resolveGithubReleases(input)
			if (releases.isEmpty()) {
				withContext(Dispatchers.Main) { onResult(false) }
				return@launchJob
			}

			val select = if (releases.size > 1) {
				val index = askSelect(releases.map { it.fileName }) ?: return@launchJob
				releases.getOrNull(index) ?: return@launchJob
			} else releases.firstOrNull() ?: return@launchJob

			val name = PluginFileLoader.resolve(select.fileName)
			if (isInstalled(name) && !askOverwrite(name)) return@launchJob

			val success = importFromGithub(select, name)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	suspend fun updatePlugin(item: PluginManageItem.Plugin): Boolean {
		val repository = item.repository ?: return false
		val release = resolveRelease(repository, item.name) ?: return false
		return if (release.tag == item.installedTag) {
			refresh()
			true
		} else {
			importFromGithub(release, item.name)
		}
	}

	fun toggleSelection(jarName: String) {
		val current = selectedPlugins.value
		selectedPlugins.value = if (jarName in current) current - jarName else current + jarName
	}

	fun clearSelection() {
		selectedPlugins.value = emptySet()
	}

	fun isSelected(jarName: String): Boolean {
		return jarName in selectedPlugins.value
	}

	suspend fun delete(): Boolean = withContext(Dispatchers.Default) {
		val select = selectedPlugins.value
		if (select.isEmpty()) return@withContext false
		var allSuccess = true
		for (jar in select) {
			try {
				mangaDynamicRepository.deletePlugin(jar)
				updatePluginsProvider.clearDto(jar)
			} catch (_: Throwable) {
				allSuccess = false
			}
		}
		selectedPlugins.value = emptySet()
		reloadPlugins(mangaDynamicRepository.getDir())
		allSuccess
	}.also { if (it) refresh() }

	suspend fun rename(item: PluginManageItem.Plugin, newRawName: String): Boolean = withContext(Dispatchers.Default) {
		val name = PluginFileLoader.resolve(newRawName)
		if (name == item.name) return@withContext true
		val pluginsDir = mangaDynamicRepository.getDir()
		val old = File(pluginsDir, item.name)
		val new = File(pluginsDir, name)
		if (new.exists()) return@withContext false
		runCatchingCancellable {
			if (old.exists() && old.renameTo(new)) {
				updatePluginsProvider.renameDto(item.name, name)
				reloadPlugins(pluginsDir)
				true
			} else {
				false
			}
		}.getOrDefault(false)
	}.also { if (it) refresh() }

	fun isInstalled(fileName: String): Boolean {
		return File(mangaDynamicRepository.getDir(), PluginFileLoader.resolve(fileName)).exists()
	}

	private fun publishFiltered() {
		val all = pluginsSnapshot
		if (all.isEmpty()) {
			content.value = listOf(
				PluginManageItem.Placeholder(
					titleResId = R.string.no_plugins,
					summaryResId = R.string.no_plugins_summary,
				),
			)
			return
		}
		val q = query
		if (q.isBlank()) {
			content.value = all
			return
		}
		val filtered = all.filter { plugin ->
			plugin.name.contains(q, true) ||
				plugin.repository?.contains(q, true) == true
		}
		content.value = filtered.ifEmpty {
			listOf(PluginManageItem.Placeholder(titleResId = R.string.nothing_found, summaryResId = null))
		}
	}

	private fun loadPluginsLocal(): List<PluginManageItem.Plugin> {
		val plugins = mangaDynamicRepository.get().sorted()
		if (plugins.isEmpty()) return emptyList()
		val meta = updatePluginsProvider.readAndCleanDto(plugins.toSet())

		return plugins.map { fileName ->
			val itemMeta = meta[fileName]
			PluginManageItem.Plugin(
				name = fileName,
				repository = itemMeta?.repository,
				installedTag = itemMeta?.tag,
				latestTag = null,
			)
		}
	}

	private suspend fun reloadPlugins(pluginsDir: File) {
		mangaDynamicRepository.load(pluginsDir)
		pluginKeyResolver.normalize(database, savedFiltersRepository)
	}
}
