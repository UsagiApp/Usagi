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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.tsukimix.core.parser.tachiyomi.ExtensionProvider
import org.draken.tsukimix.core.parser.tachiyomi.NativeExtManager
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiRuntime
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionArtifact
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.DirectTachiyomiPluginMetadata
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import tsuki.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PluginsManageViewModel
	@Inject
	constructor(
		@param:ApplicationContext private val context: Context,
		private val database: MangaDatabase,
		private val savedFiltersRepository: SavedFiltersRepository,
		private val updatePluginsProvider: UpdatePluginsProvider,
		private val settings: AppSettings,
		private val mangaDynamicRepository: MangaDynamicRepository,
		private val pluginKeyResolver: PluginKeyResolver,
		private val directManager: NativeExtManager,
		private val tachiyomiRuntime: TachiyomiRuntime,
		private val catalogProvider: ExtensionProvider,
	) : BaseViewModel() {
		val content = MutableStateFlow<List<PluginManageItem>>(emptyList())
		val selectedPlugins = MutableStateFlow<Set<String>>(emptySet())

		@Volatile
		private var pluginsSnapshot = emptyList<PluginManageItem.Plugin>()

		@Volatile
		private var tachiyomiSnapshot = emptyList<PluginManageItem.Tachiyomi>()

		@Volatile
		private var query = ""

		@Volatile
		private var pendingImportUrl: String? = null

		init {
			refresh()
		}

		fun refresh() {
			launchJob(Dispatchers.Default) {
				val localPlugins = loadPluginsLocal()
				pluginsSnapshot = localPlugins
				refreshTachiyomiItems(catalogProvider.loadSavedCached())
				publishFiltered()

				launch {
					runCatching { tachiyomiRuntime.ensureReady() }
					val refreshed = catalogProvider.loadSaved()
					refreshTachiyomiItems(refreshed)
				}
				if (localPlugins.isNotEmpty()) {
					launch {
						val updatedPlugins =
							coroutineScope {
								localPlugins
									.map { plugin ->
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

		suspend fun resolveRelease(
			input: String,
			name: String? = null,
		): ExternalPluginDto? =
			withContext(Dispatchers.Default) {
				val repository = updatePluginsProvider.resolve(input) ?: return@withContext null
				updatePluginsProvider.requestRelease(repository, name)
			}

		suspend fun resolveGithubReleases(input: String): List<ExternalPluginDto> =
			withContext(Dispatchers.Default) {
				val repository = updatePluginsProvider.resolve(input) ?: return@withContext emptyList()
				val tag = updatePluginsProvider.requestTag(repository) ?: return@withContext emptyList()
				updatePluginsProvider.requestPlugins(repository, tag)
			}

		suspend fun importFromUri(
			uri: Uri,
			fileName: String,
		): Boolean =
			withContext(Dispatchers.Default) {
				val safeName = PluginFileLoader.resolve(fileName)
				runCatchingCancellable {
					val pluginsDir = mangaDynamicRepository.getDir()
					PluginFileLoader.copyFromUri(context, uri, File(pluginsDir, safeName))
					updatePluginsProvider.clearDto(safeName)
					reloadPlugins(pluginsDir)
				}.isSuccess
			}.also { if (it) refresh() }

		suspend fun importFromGithub(
			release: ExternalPluginDto,
			fileName: String = release.fileName,
		): Boolean =
			updatePluginsProvider
				.installPlugin(release, PluginFileLoader.resolve(fileName))
				.also { if (it) refresh() }

		suspend fun importPlugin(
			uri: Uri,
			getOriginalName: (Uri) -> String?,
			askName: suspend (String) -> String?,
			askOverwrite: suspend (String) -> Boolean,
		): Boolean? =
			withContext(Dispatchers.Default) {
				val originalName = getOriginalName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
				val tempDir = File(context.cacheDir, "imports").also { it.mkdirs() }
				val tempFile = File(tempDir, originalName)
				try {
					PluginFileLoader.copyFromUri(context, uri, tempFile)
					val tachiyomiInstalled = directManager.installLocal(tempFile, originalName)
					if (tachiyomiInstalled) {
						tachiyomiRuntime.ensureReady(forceRefresh = true)
						refresh()
						return@withContext true
					}
				} finally {
					tempFile.delete()
				}

				val pluginName = askName(originalName.removeSuffix(".jar"))?.trim().orEmpty()
				if (pluginName.isBlank()) return@withContext null

				val fileName = PluginFileLoader.resolve(pluginName)
				if (isInstalled(fileName) && !askOverwrite(fileName)) return@withContext null

				importFromUri(uri, fileName)
			}

		suspend fun importUrl(
			askInput: suspend () -> String?,
			askOverwrite: suspend (String) -> Boolean,
		): Boolean? =
			withContext(Dispatchers.Default) {
				val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext null
				pendingImportUrl = input
				publishFiltered()
				try {
					val trimmed = input.trim()
					// 1. Direct .jar/.apk file URL or direct release download URL
					if (trimmed.endsWith(".jar", ignoreCase = true) ||
						trimmed.contains(".jar?", ignoreCase = true) ||
						trimmed.endsWith(".apk", ignoreCase = true) ||
						trimmed.contains(".apk?", ignoreCase = true) ||
						trimmed.contains("/releases/download/")
					) {
						if (updatePluginsProvider.importFromUrl(trimmed)) {
							refresh()
							return@withContext true
						}
					}

					// 2. Fast check: Tsuki/Kotatsu plugin repository on GitHub (single latest release with .jar plugin)
					val releases = resolveGithubReleases(trimmed)
					val select = releases.firstOrNull()
					val isTsukiPlugin =
						select != null &&
							!select.fileName.startsWith("tachiyomi-", ignoreCase = true) &&
							!select.fileName.startsWith("eu.kanade.tachiyomi", ignoreCase = true)

					if (isTsukiPlugin) {
						val name = PluginFileLoader.resolve(select.fileName)
						if (isInstalled(name) && !askOverwrite(name)) return@withContext null
						if (importFromGithub(select, name)) {
							refresh()
							return@withContext true
						}
					}

					// 3. Tachiyomi/Mihon extensions repository
					val artifacts = catalogProvider.load(trimmed)
					if (artifacts.isNotEmpty()) {
						artifacts.forEach { catalogProvider.restorePackage(it.packageName) }
						catalogProvider.saveRepository(trimmed)
						refreshTachiyomiItems(catalogProvider.loadSaved() + artifacts)
						return@withContext true
					}

					// 4. Fallback: Any other GitHub release asset
					if (select != null) {
						val name = PluginFileLoader.resolve(select.fileName)
						if (isInstalled(name) && !askOverwrite(name)) return@withContext null
						if (importFromGithub(select, name)) {
							refresh()
							return@withContext true
						}
					}

					if (updatePluginsProvider.importFromUrl(trimmed)) {
						refresh()
						return@withContext true
					}

					return@withContext false
				} finally {
					pendingImportUrl = null
					publishFiltered()
				}
			}

		suspend fun renameTachiyomi(
			item: PluginManageItem.Tachiyomi,
			name: String,
		): Boolean =
			withContext(Dispatchers.Default) {
				catalogProvider.setRepositoryName(item.repositoryUrl, name)
				DirectTachiyomiPluginMetadata.update(directManager.installed.value) { catalogProvider.repositoryName(it) }
				refresh()
				true
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

		fun toggleTachiyomiSelection(item: PluginManageItem.Tachiyomi) {
			toggleSelection(tachiyomiSelectionKey(item.repositoryUrl))
		}

		fun isTachiyomiSelected(item: PluginManageItem.Tachiyomi): Boolean = tachiyomiSelectionKey(item.repositoryUrl) in selectedPlugins.value

		fun clearSelection() {
			selectedPlugins.value = emptySet()
		}

		fun isSelected(jarName: String): Boolean = jarName in selectedPlugins.value

		suspend fun delete(): Boolean =
			withContext(Dispatchers.Default) {
				val select = selectedPlugins.value
				if (select.isEmpty()) return@withContext false
				var allSuccess = true
				var hasLocalPlugins = false
				for (key in select) {
					if (key.startsWith(TACHIYOMI_SELECTION_PREFIX)) {
						val repositoryUrl = key.removePrefix(TACHIYOMI_SELECTION_PREFIX)
						val item = tachiyomiSnapshot.firstOrNull { it.repositoryUrl == repositoryUrl }
						if (item == null) {
							allSuccess = false
							continue
						}
						val installedPackages =
							(item.installed.map { it.packageName } + listOfNotNull(item.repositoryUrl.removePrefix("local://").takeIf { item.isLocal })).distinct()
						installedPackages.forEach { directManager.remove(it) }
						item.artifacts.forEach { catalogProvider.restorePackage(it.packageName) }
						catalogProvider.removeRepository(repositoryUrl)
					} else {
						hasLocalPlugins = true
						try {
							mangaDynamicRepository.deletePlugin(key)
							updatePluginsProvider.clearDto(key)
						} catch (_: Throwable) {
							allSuccess = false
						}
					}
				}
				selectedPlugins.value = emptySet()
				if (hasLocalPlugins) reloadPlugins(mangaDynamicRepository.getDir())
				tachiyomiRuntime.ensureReady(forceRefresh = true)
				allSuccess
			}.also { if (it) refresh() }

		suspend fun rename(
			item: PluginManageItem.Plugin,
			newRawName: String,
		): Boolean =
			withContext(Dispatchers.Default) {
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

		fun isInstalled(fileName: String): Boolean = File(mangaDynamicRepository.getDir(), PluginFileLoader.resolve(fileName)).exists()

		private fun refreshTachiyomiItems(artifacts: List<TachiyomiExtensionArtifact>) {
			val failures = directManager.failed.value
			val installed = directManager.installed.value.distinctBy { it.packageName }
			val uniqueArtifacts = artifacts.distinctBy { it.packageName }
			val artifactRepositoryByPackage = uniqueArtifacts.associate { it.packageName to canonicalRepository(it.repositoryUrl) }
			val artifactsByRepository = uniqueArtifacts.groupBy { canonicalRepository(it.repositoryUrl) }
			val installedByRepository =
				installed.groupBy { record ->
					record.repositoryUrl
						.takeIf { it.isNotBlank() }
						?.let(::canonicalRepository)
						?: artifactRepositoryByPackage[record.packageName]
						?: INSTALLED_REPOSITORY_FALLBACK
				}
			val repositories = (artifactsByRepository.keys + installedByRepository.keys).distinct()
			val items =
				repositories.map { repositoryUrl ->
					val repositoryArtifacts = artifactsByRepository[repositoryUrl].orEmpty()
					val repositoryInstalled = installedByRepository[repositoryUrl].orEmpty()
					val packageNames = (repositoryArtifacts.map { it.packageName } + repositoryInstalled.map { it.packageName }).toSet()
					PluginManageItem.Tachiyomi(
						repositoryUrl = repositoryUrl,
						artifacts = repositoryArtifacts,
						installed = repositoryInstalled,
						failures = failures.filter { it.packageName in packageNames },
						customName = catalogProvider.repositoryName(repositoryUrl),
					)
				}
			DirectTachiyomiPluginMetadata.update(directManager.installed.value) { catalogProvider.repositoryName(it) }
			tachiyomiSnapshot = items.sortedBy { it.displayName.lowercase() }
			publishFiltered()
		}

		private fun canonicalRepository(value: String): String = catalogProvider.normalizeUrl(value) ?: value.trim().removeSuffix("/")

		private fun publishFiltered() {
			val all: List<PluginManageItem> =
				buildList {
					pendingImportUrl?.let { add(PluginManageItem.Loading(it)) }
					addAll(pluginsSnapshot)
					addAll(tachiyomiSnapshot)
				}
			if (all.isEmpty()) {
				content.value = listOf(PluginManageItem.Placeholder(R.string.no_plugins, R.string.no_plugins_summary))
				return
			}
			val q = query
			if (q.isBlank()) {
				content.value = all
				return
			}
			val filtered =
				all.filter { item ->
					when (item) {
						is PluginManageItem.Loading -> {
							true
						}

						is PluginManageItem.Plugin -> {
							item.name.contains(q, true) || item.repository?.contains(q, true) == true
						}

						is PluginManageItem.Tachiyomi -> {
							item.displayName.contains(q, true) || item.repositoryLabel.contains(q, true) || item.repositoryUrl.contains(q, true)
						}

						is PluginManageItem.Placeholder -> {
							false
						}
					}
				}
			content.value = filtered.ifEmpty { listOf(PluginManageItem.Placeholder(R.string.nothing_found, null)) }
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

		private fun tachiyomiSelectionKey(repositoryUrl: String): String = TACHIYOMI_SELECTION_PREFIX + repositoryUrl

		private suspend fun reloadPlugins(pluginsDir: File) {
			mangaDynamicRepository.load(pluginsDir)
			pluginKeyResolver.normalize(database, savedFiltersRepository)
		}

		private companion object {
			const val TACHIYOMI_SELECTION_PREFIX = "tachiyomi:"
			const val INSTALLED_REPOSITORY_FALLBACK = "installed://direct"
		}
	}
