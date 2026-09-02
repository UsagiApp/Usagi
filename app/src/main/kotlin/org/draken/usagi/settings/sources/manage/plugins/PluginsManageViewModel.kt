package org.draken.usagi.settings.sources.manage.plugins

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.tsukimix.core.parser.external.ExtRuntime
import org.draken.tsukimix.core.parser.external.ExtensionProvider
import org.draken.tsukimix.core.parser.external.NativeExtManager
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.DirectExternalPluginMetadata
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import tsuki.util.runCatchingCancellable
import java.io.File
import java.net.URI
import javax.inject.Inject

sealed class OperationResult(
	@StringRes val messageResId: Int,
) {
	class Success(
		@StringRes messageResId: Int,
	) : OperationResult(messageResId)

	class Failure(
		@StringRes messageResId: Int,
	) : OperationResult(messageResId)
}

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
		private val runtime: ExtRuntime,
		private val catalogProvider: ExtensionProvider,
	) : BaseViewModel() {
		val content = MutableStateFlow<List<PluginManageItem>>(emptyList())
		val selectedPlugins = MutableStateFlow<Set<String>>(emptySet())
		val operationResult = MutableEventFlow<OperationResult>()

		@Volatile private var pluginsSnapshot = emptyList<PluginManageItem.Plugin>()

		@Volatile private var extensionSnapshot = emptyList<PluginManageItem.Extension>()

		@Volatile private var query = ""

		@Volatile private var pendingImportUrl: String? = null

		init {
			refresh()
		}

		fun refresh() {
			launchJob(Dispatchers.Default) {
				pluginsSnapshot = loadPluginsLocal()
				refreshExtensionItems(catalogProvider.loadSavedCached())
				publishFiltered()

				launch {
					runCatching { runtime.ensureReady() }
					refreshExtensionItems(catalogProvider.loadSaved())
				}
				if (pluginsSnapshot.isNotEmpty()) {
					launch {
						pluginsSnapshot =
							coroutineScope {
								pluginsSnapshot
									.map { p ->
										async {
											val repo = p.repository ?: return@async p
											val tag = updatePluginsProvider.requestTag(repo) ?: return@async p
											p.copy(latestTag = tag)
										}
									}.awaitAll()
							}
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
				launchJob(Dispatchers.Default) { updatePluginsProvider.runAutoUpdate(settings) }
			}
		}

		suspend fun importFromUri(
			uri: Uri,
			fileName: String,
		): Boolean =
			withContext(Dispatchers.Default) {
				val safeName = PluginFileLoader.resolve(fileName)
				runCatchingCancellable {
					val dir = mangaDynamicRepository.getDir()
					PluginFileLoader.copyFromUri(context, uri, File(dir, safeName))
					updatePluginsProvider.clearDto(safeName)
					reloadPlugins(dir)
				}.isSuccess
			}.also { if (it) refresh() }

		fun importPlugin(
			uri: Uri,
			getOriginalName: (Uri) -> String?,
			askName: suspend (String) -> String?,
			askOverwrite: suspend (String) -> Boolean,
		) {
			launchJob(Dispatchers.Default) {
				val rawName = getOriginalName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
				val tempFile = File(context.cacheDir.resolve("imports").also { it.mkdirs() }, rawName)
				try {
					PluginFileLoader.copyFromUri(context, uri, tempFile)
					if (directManager.installLocal(tempFile, rawName)) {
						runtime.ensureReady(forceRefresh = true)
						refresh()
						operationResult.call(OperationResult.Success(R.string.load_success))
						return@launchJob
					}
				} finally {
					tempFile.delete()
				}
				val name = askName(rawName.removeSuffix(".jar"))?.trim().orEmpty()
				if (name.isBlank()) return@launchJob
				val safeName = PluginFileLoader.resolve(name)
				if (isInstalled(safeName) && !askOverwrite(safeName)) return@launchJob
				val ok = importFromUri(uri, safeName)
				operationResult.call(
					if (ok) {
						OperationResult.Success(R.string.load_success)
					} else {
						OperationResult.Failure(R.string.load_failed)
					},
				)
			}
		}

		fun importUrl(
			askInput: suspend () -> String?,
			askOverwrite: suspend (String) -> Boolean,
		) {
			launchJob(Dispatchers.Default) {
				val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@launchJob
				pendingImportUrl = input
				publishFiltered()
				try {
					if (updatePluginsProvider.importFromUrl(input)) {
						refresh()
						operationResult.call(OperationResult.Success(R.string.load_success))
						return@launchJob
					}
					val releases =
						updatePluginsProvider
							.resolve(input)
							?.let { repo ->
								updatePluginsProvider.requestTag(repo)?.let { tag -> updatePluginsProvider.requestPlugins(repo, tag) }
							}.orEmpty()
					if (releases.isNotEmpty() && releases.size <= 3) {
						val select = releases.first()
						val safeName = PluginFileLoader.resolve(select.fileName)
						if (isInstalled(safeName) && !askOverwrite(safeName)) return@launchJob
						if (updatePluginsProvider.installPlugin(select, safeName)) {
							refresh()
							operationResult.call(OperationResult.Success(R.string.load_success))
							return@launchJob
						}
					}
					val artifacts = catalogProvider.load(input)
					if (artifacts.isNotEmpty()) {
						artifacts.forEach { catalogProvider.restorePackage(it.packageName) }
						catalogProvider.saveRepository(input)
						refreshExtensionItems(catalogProvider.loadSaved() + artifacts)
						operationResult.call(OperationResult.Success(R.string.load_success))
						return@launchJob
					}
					operationResult.call(OperationResult.Failure(R.string.load_failed))
				} finally {
					pendingImportUrl = null
					publishFiltered()
				}
			}
		}

		fun renameExtension(
			item: PluginManageItem.Extension,
			name: String,
		) {
			launchJob(Dispatchers.Default) {
				catalogProvider.setRepositoryName(item.repositoryUrl, name)
				DirectExternalPluginMetadata.update(directManager.installed.value) {
					catalogProvider.repositoryName(it)
				}
				refresh()
				operationResult.call(OperationResult.Success(R.string.load_success))
			}
		}

		fun updatePlugin(item: PluginManageItem.Plugin) {
			launchJob(Dispatchers.Default) {
				val repo =
					item.repository ?: run {
						operationResult.call(OperationResult.Failure(R.string.load_failed))
						return@launchJob
					}
				val rel = updatePluginsProvider.requestRelease(repo, item.name)
				val ok =
					if (rel != null && rel.tag != item.installedTag) {
						updatePluginsProvider.installPlugin(rel, item.name)
					} else {
						rel != null
					}
				if (ok) refresh()
				operationResult.call(
					if (ok) {
						OperationResult.Success(R.string.load_success)
					} else {
						OperationResult.Failure(R.string.load_failed)
					},
				)
			}
		}

		fun toggleSelection(jarName: String) {
			val c = selectedPlugins.value
			selectedPlugins.value = if (jarName in c) c - jarName else c + jarName
		}

		fun toggleExtensionSelection(item: PluginManageItem.Extension) = toggleSelection(PREFIX + item.repositoryUrl)

		fun isExtensionSelected(item: PluginManageItem.Extension) = (PREFIX + item.repositoryUrl) in selectedPlugins.value

		fun clearSelection() {
			selectedPlugins.value = emptySet()
		}

		fun isSelected(jarName: String) = jarName in selectedPlugins.value

		fun delete() {
			launchJob(Dispatchers.Default) {
				val select = selectedPlugins.value
				if (select.isEmpty()) return@launchJob
				var ok = true
				var hasLocal = false
				for (key in select) {
					if (key.startsWith(PREFIX)) {
						val url = key.removePrefix(PREFIX)
						val item =
							extensionSnapshot.firstOrNull { it.repositoryUrl == url } ?: run {
								ok = false
								continue
							}
						val pkgs =
							(
								item.installed.map { it.packageName } +
									listOfNotNull(url.removePrefix("local://").takeIf { item.isLocal })
							).distinct()
						pkgs.forEach { directManager.remove(it) }
						item.artifacts.forEach { catalogProvider.restorePackage(it.packageName) }
						catalogProvider.removeRepository(url)
					} else {
						hasLocal = true
						try {
							mangaDynamicRepository.deletePlugin(key)
							updatePluginsProvider.clearDto(key)
						} catch (_: Throwable) {
							ok = false
						}
					}
				}
				selectedPlugins.value = emptySet()
				if (hasLocal) reloadPlugins(mangaDynamicRepository.getDir())
				runtime.ensureReady(forceRefresh = true)
				if (ok) refresh()
				operationResult.call(
					if (ok) {
						OperationResult.Success(R.string.removal_completed)
					} else {
						OperationResult.Failure(R.string.load_failed)
					},
				)
			}
		}

		fun rename(
			item: PluginManageItem.Plugin,
			newRawName: String,
		) {
			launchJob(Dispatchers.Default) {
				val name = PluginFileLoader.resolve(newRawName)
				if (name == item.name) {
					operationResult.call(OperationResult.Success(R.string.load_success))
					return@launchJob
				}
				val dir = mangaDynamicRepository.getDir()
				val old = File(dir, item.name)
				val new = File(dir, name)
				if (new.exists()) {
					operationResult.call(OperationResult.Failure(R.string.load_failed))
					return@launchJob
				}
				val ok =
					runCatchingCancellable {
						if (old.exists() && old.renameTo(new)) {
							updatePluginsProvider.renameDto(item.name, name)
							reloadPlugins(dir)
							true
						} else {
							false
						}
					}.getOrDefault(false)
				if (ok) refresh()
				operationResult.call(
					if (ok) {
						OperationResult.Success(R.string.load_success)
					} else {
						OperationResult.Failure(R.string.load_failed)
					},
				)
			}
		}

		fun isInstalled(fileName: String) = File(mangaDynamicRepository.getDir(), PluginFileLoader.resolve(fileName)).exists()

		private fun refreshExtensionItems(artifacts: List<ExtArtifact>) {
			val failures = directManager.failed.value
			val installed = directManager.installed.value.distinctBy { it.packageName }
			val uniqueArtifacts = artifacts.distinctBy { it.packageName }
			val artifactRepoByPkg = uniqueArtifacts.associate { it.packageName to canonicalRepo(it.repositoryUrl) }
			val artsByRepo = uniqueArtifacts.groupBy { canonicalRepo(it.repositoryUrl) }
			val instByRepo =
				installed.groupBy { r ->
					r.repositoryUrl.takeIf { it.isNotBlank() }?.let(::canonicalRepo)
						?: artifactRepoByPkg[r.packageName]
						?: "installed://direct"
				}
			val repos = (artsByRepo.keys + instByRepo.keys).distinct()
			extensionSnapshot =
				repos
					.map { url ->
						val repoArts = artsByRepo[url].orEmpty()
						val repoInst = instByRepo[url].orEmpty()
						val pkgs = (repoArts.map { it.packageName } + repoInst.map { it.packageName }).toSet()
						val isLocal = url.startsWith("local:") || url.startsWith("installed:")
						val label = if (isLocal) context.getString(R.string.local_storage) else parseRepoLabel(url)
						val custom = catalogProvider.repositoryName(url)
						val defaultDisp =
							if (isLocal) {
								repoInst.firstOrNull()?.name?.takeIf { it.isNotBlank() }
							} else {
								label.substringBefore('/').ifBlank { context.getString(R.string.external_source) }
							} ?: label.substringBefore('/').ifBlank { context.getString(R.string.external_source) }
						val disp = custom?.trim()?.takeIf { it.isNotBlank() } ?: defaultDisp
						PluginManageItem.Extension(
							url,
							label,
							disp,
							repoArts,
							repoInst,
							failures.filter { it.packageName in pkgs },
							custom,
						)
					}.sortedBy { it.displayName.lowercase() }
			DirectExternalPluginMetadata.update(directManager.installed.value) {
				catalogProvider.repositoryName(it)
			}
			publishFiltered()
		}

		private fun parseRepoLabel(url: String) =
			runCatching {
				val uri = URI(url)
				val segs =
					uri.path
						.trim('/')
						.split('/')
						.filter { it.isNotBlank() }
				when (uri.host?.lowercase()) {
					"raw.githubusercontent.com", "github.com", "www.github.com" -> segs.take(2).joinToString("/")
					else -> uri.host.orEmpty().ifBlank { url }
				}
			}.getOrDefault(url)

		private fun canonicalRepo(v: String) = catalogProvider.normalizeUrl(v) ?: v.trim().removeSuffix("/")

		private fun publishFiltered() {
			val all =
				buildList {
					pendingImportUrl?.let { add(PluginManageItem.Loading(it)) }
					addAll(pluginsSnapshot)
					addAll(extensionSnapshot)
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
			content.value =
				all
					.filter { item ->
						when (item) {
							is PluginManageItem.Loading -> {
								true
							}

							is PluginManageItem.Plugin -> {
								item.name.contains(q, true) || item.repository?.contains(q, true) == true
							}

							is PluginManageItem.Extension -> {
								item.displayName.contains(q, true) ||
									item.repositoryLabel.contains(q, true) ||
									item.repositoryUrl.contains(q, true)
							}

							is PluginManageItem.Placeholder -> {
								false
							}
						}
					}.ifEmpty { listOf(PluginManageItem.Placeholder(R.string.nothing_found, null)) }
		}

		private fun loadPluginsLocal(): List<PluginManageItem.Plugin> {
			val plugins = mangaDynamicRepository.get().sorted()
			if (plugins.isEmpty()) return emptyList()
			val meta = updatePluginsProvider.readAndCleanDto(plugins.toSet())
			return plugins.map { f ->
				val m = meta[f]
				PluginManageItem.Plugin(f, m?.repository, m?.tag, null)
			}
		}

		private suspend fun reloadPlugins(dir: File) {
			mangaDynamicRepository.load(dir)
			pluginKeyResolver.normalize(database, savedFiltersRepository)
		}

		private companion object {
			const val PREFIX = "ext_repo:"
		}
	}
