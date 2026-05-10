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
import org.draken.usagi.core.model.PluginSourceKeyNormalizer
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PluginsManageViewModel @Inject constructor(
	@param:ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val savedFiltersRepository: SavedFiltersRepository,
	private val updatePluginsProvider: UpdatePluginsProvider,
	private val settings: AppSettings,
) : BaseViewModel() {

	val content = MutableStateFlow<List<PluginManageItem>>(emptyList())

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

	suspend fun resolveGithubRelease(input: String): ExternalPluginDto? = withContext(Dispatchers.Default) {
		val repository = updatePluginsProvider.normalizeRepository(input) ?: return@withContext null
		updatePluginsProvider.requestRelease(repository)
	}

	suspend fun importFromUri(uri: Uri, fileName: String): Boolean = withContext(Dispatchers.Default) {
		val safeName = sanitizeJarFileName(fileName)
		runCatchingCancellable {
			val pluginsDir = PluginFileLoader.pluginsDir(context)
			PluginFileLoader.copyFromUri(context, uri, File(pluginsDir, safeName))
			updatePluginsProvider.clearDto(safeName)
			reloadPlugins(pluginsDir)
		}.isSuccess
	}.also { if (it) refresh() }

	suspend fun importFromGithub(release: ExternalPluginDto, fileName: String = release.fileName): Boolean =
		updatePluginsProvider.installPlugin(release, sanitizeJarFileName(fileName))
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

			val fileName = sanitizeJarFileName(pluginName)
			if (isInstalled(fileName) && !askOverwrite(fileName)) return@launchJob

			val success = importFromUri(uri, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	fun importGithubPlugin(
		askInput: suspend () -> String?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit
	) {
		launchJob(Dispatchers.Default) {
			val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@launchJob
			val release = resolveGithubRelease(input)
			if (release == null) {
				withContext(Dispatchers.Main) { onResult(false) }
				return@launchJob
			}

			val fileName = sanitizeJarFileName(release.fileName)
			if (isInstalled(fileName) && !askOverwrite(fileName)) return@launchJob

			val success = importFromGithub(release, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	suspend fun updatePlugin(item: PluginManageItem.Plugin): Boolean {
		val repository = item.repository ?: return false
		val release = resolveGithubRelease(repository) ?: return false
		return if (release.tag == item.installedTag) {
			refresh()
			true
		} else {
			importFromGithub(release, item.jarName)
		}
	}

	suspend fun deletePlugin(item: PluginManageItem.Plugin): Boolean = withContext(Dispatchers.Default) {
		runCatchingCancellable {
			DynamicParserManager.deletePlugin(context, item.jarName)
			updatePluginsProvider.clearDto(item.jarName)
		}.isSuccess
	}.also {
		if (it) refresh()
	}

	fun sanitizeJarFileName(rawName: String): String {
		val sanitized = rawName
			.trim()
			.replace('/', '_')
			.replace('\\', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}.jar" }
		return if (sanitized.lowercase(Locale.ROOT).endsWith(".jar")) sanitized else "$sanitized.jar"
	}

	fun isInstalled(fileName: String): Boolean {
		return File(PluginFileLoader.pluginsDir(context), sanitizeJarFileName(fileName)).exists()
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
			plugin.jarName.contains(q, ignoreCase = true) ||
					plugin.repository?.contains(q, ignoreCase = true) == true
		}
		content.value = filtered.ifEmpty {
			listOf(PluginManageItem.Placeholder(titleResId = R.string.nothing_found, summaryResId = null))
		}
	}

	private fun loadPluginsLocal(): List<PluginManageItem.Plugin> {
		val plugins = DynamicParserManager.getInstalledPlugins(context).sorted()
		if (plugins.isEmpty()) return emptyList()
		val meta = updatePluginsProvider.readAndCleanDto(plugins.toSet())

		return plugins.map { fileName ->
			val itemMeta = meta[fileName]
			PluginManageItem.Plugin(
				jarName = fileName,
				repository = itemMeta?.repository,
				installedTag = itemMeta?.tag,
				latestTag = null,
			)
		}
	}

	private suspend fun reloadPlugins(pluginsDir: File) {
		DynamicParserManager.loadParsersFromDirectory(context, pluginsDir)
		PluginSourceKeyNormalizer.normalize(database, savedFiltersRepository)
	}
}

