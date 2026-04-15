package org.draken.usagi.settings.sources.manage.plugins

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.PluginSourceKeyNormalizer
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoIndex
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepository
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoStore
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PluginsManageViewModel @Inject constructor(
	@param:ApplicationContext private val context: Context,
	@param:BaseHttpClient private val okHttpClient: OkHttpClient,
	private val database: MangaDatabase,
	private val savedFiltersRepository: SavedFiltersRepository,
) : BaseViewModel() {

	val content = MutableStateFlow<List<PluginManageItem>>(emptyList())
	private val prefs by lazy {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

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
							if (plugin.isTachiyomiRepo) return@async plugin
							val repo = plugin.repository ?: return@async plugin
							if (!REPOSITORY_REGEX.matches(repo) || splitRepository(repo) == null) return@async plugin
							val latest = requestLatestTag(repo) ?: return@async plugin
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

	suspend fun resolveGithubRelease(input: String): ExternalPluginDto? = withContext(Dispatchers.Default) {
		val repository = normalizeRepository(input) ?: return@withContext null
		requestLatestRelease(repository)
	}

	suspend fun importFromUri(uri: Uri, fileName: String): Boolean = withContext(Dispatchers.Default) {
		val safeName = sanitizePluginFileName(fileName)
		runCatchingCancellable {
			val pluginsDir = PluginFileLoader.pluginsDir(context)
			PluginFileLoader.copyFromUri(context, uri, File(pluginsDir, safeName))
			clearGithubMeta(safeName)
			TachiyomiRepoStore.removeInstalledPluginMeta(context, safeName)
			reloadPlugins(pluginsDir)
		}.isSuccess
	}.also { if (it) refresh() }

	suspend fun importFromGithub(release: ExternalPluginDto, fileName: String = release.fileName): Boolean =
		importFromUrl(
			downloadUrl = release.downloadUrl,
			fileName = fileName,
			meta = GithubMeta(repository = release.repository, tag = release.tag),
		)

	fun importPlugin(
		uri: Uri,
		getOriginalName: (Uri) -> String?,
		askName: suspend (String) -> String?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit,
	) {
		launchJob(Dispatchers.Default) {
			val originalName = getOriginalName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
			val preferredExtension = originalName.pluginFileExtension() ?: DEFAULT_PLUGIN_EXTENSION
			val pluginName = askName(originalName.removePluginFileSuffix())?.trim().orEmpty()
			if (pluginName.isBlank()) return@launchJob

			val fileName = sanitizePluginFileName(pluginName, preferredExtension)
			if (isInstalled(fileName) && !askOverwrite(fileName)) return@launchJob

			val success = importFromUri(uri, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	fun importGithubPlugin(
		askInput: suspend () -> String?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit,
	) {
		launchJob(Dispatchers.Default) {
			val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@launchJob

			val tachiyomiIndexUrl = TachiyomiRepoIndex.normalizeRepoUrl(input)
			if (tachiyomiIndexUrl != null) {
				val success = registerTachiyomiRepository(tachiyomiIndexUrl)
				withContext(Dispatchers.Main) { onResult(success) }
				return@launchJob
			}

			val release = resolveGithubRelease(input)
			if (release == null) {
				withContext(Dispatchers.Main) { onResult(false) }
				return@launchJob
			}

			val fileName = sanitizePluginFileName(release.fileName, release.fileName.pluginFileExtension())
			if (isInstalled(fileName) && !askOverwrite(fileName)) return@launchJob

			val success = importFromGithub(release, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	private suspend fun registerTachiyomiRepository(indexUrl: String): Boolean {
		return runCatchingCancellable {
			val request = Request.Builder().get().url(indexUrl).build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return@use false
				val body = response.body.string()
				if (body.isBlank()) return@use false
				val sources = TachiyomiRepoIndex.parseIndex(indexUrl, body)
				if (sources.isEmpty()) return@use false
				val ownerTag = sources.first().repoOwnerTag
				TachiyomiRepoStore.saveRepository(
					context = context,
					repository = TachiyomiRepository(ownerTag = ownerTag, indexUrl = indexUrl),
				)
				refresh()
				true
			}
		}.getOrElse { false }
	}

	suspend fun updatePlugin(item: PluginManageItem.Plugin): Boolean {
		if (item.isTachiyomiRepo) return false
		val repository = item.repository ?: return false
		if (!REPOSITORY_REGEX.matches(repository) || splitRepository(repository) == null) return false
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
			if (item.isTachiyomiRepo) {
				val pluginsDir = PluginFileLoader.pluginsDir(context)
				val managedFiles = TachiyomiRepoStore.findInstalledPluginFilesByOwner(context, item.jarName)
				managedFiles.forEach { fileName ->
					File(pluginsDir, fileName).takeIf { it.exists() }?.delete()
					TachiyomiRepoStore.removeInstalledPluginMeta(context, fileName)
				}
				val removedRepo = TachiyomiRepoStore.removeRepository(context, item.jarName)
				if (managedFiles.isNotEmpty()) {
					reloadPlugins(pluginsDir)
				}
				removedRepo || managedFiles.isNotEmpty()
			} else {
				DynamicParserManager.deletePlugin(context, item.jarName)
				clearGithubMeta(item.jarName)
				true
			}
		}.getOrElse { false }
	}.also {
		if (it) refresh()
	}

	fun sanitizePluginFileName(rawName: String, preferredExtension: String? = null): String {
		val sanitized = rawName
			.trim()
			.replace('/', '_')
			.replace('\\', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}${preferredExtension ?: DEFAULT_PLUGIN_EXTENSION}" }
		val lower = sanitized.lowercase(Locale.ROOT)
		if (lower.endsWith(".jar") || lower.endsWith(".apk")) {
			return sanitized
		}
		val normalizedExtension = when (preferredExtension?.lowercase(Locale.ROOT)) {
			".apk", "apk" -> ".apk"
			".jar", "jar" -> ".jar"
			else -> DEFAULT_PLUGIN_EXTENSION
		}
		return "$sanitized$normalizedExtension"
	}

	fun isInstalled(fileName: String): Boolean {
		return File(PluginFileLoader.pluginsDir(context), sanitizePluginFileName(fileName)).exists()
	}

	private suspend fun importFromUrl(
		downloadUrl: String,
		fileName: String,
		meta: GithubMeta?,
	): Boolean = withContext(Dispatchers.Default) {
		val safeName = sanitizePluginFileName(fileName, fileName.pluginFileExtension())
		runCatchingCancellable {
			val pluginsDir = PluginFileLoader.pluginsDir(context)
			val outFile = File(pluginsDir, safeName)
			val request = Request.Builder().get().url(downloadUrl).build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) throw IOException()
				PluginFileLoader.copyFromStream(outFile, response.body.byteStream())
			}
			if (meta != null) {
				saveGithubMeta(safeName, meta.repository, meta.tag)
				TachiyomiRepoStore.removeInstalledPluginMeta(context, safeName)
			} else {
				clearGithubMeta(safeName)
			}
			reloadPlugins(pluginsDir)
		}.isSuccess
	}.also {
		if (it) refresh()
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
		val pluginFiles = DynamicParserManager.getInstalledPlugins(context).sorted()
		val installedFiles = pluginFiles.toSet()
		val githubMeta = readAndCleanupMeta(installedFiles)
		TachiyomiRepoStore.cleanupInstalledPluginMeta(context, installedFiles)
		val tachiyomiMeta = pluginFiles.associateWith { TachiyomiRepoStore.getInstalledPluginMeta(context, it) }

		val localPlugins = pluginFiles.mapNotNull { fileName ->
			val itemMeta = githubMeta[fileName]
			val tachiMeta = tachiyomiMeta[fileName]
			if (tachiMeta != null) return@mapNotNull null
			PluginManageItem.Plugin(
				jarName = fileName,
				repository = itemMeta?.repository,
				installedTag = itemMeta?.tag,
				latestTag = null,
				isTachiyomiRepo = false,
			)
		}

		val repos = TachiyomiRepoStore.listRepositories(context).map { repo ->
			PluginManageItem.Plugin(
				jarName = repo.ownerTag,
				repository = repo.indexUrl,
				installedTag = null,
				latestTag = null,
				isTachiyomiRepo = true,
			)
		}

		return (repos + localPlugins).sortedBy { it.displayName.lowercase(Locale.ROOT) }
	}

	private suspend fun reloadPlugins(pluginsDir: File) {
		DynamicParserManager.loadParsersFromDirectory(context, pluginsDir)
		PluginSourceKeyNormalizer.normalize(database, savedFiltersRepository)
	}

	private suspend fun requestLatestRelease(repository: String): ExternalPluginDto? {
		val tag = requestLatestTag(repository) ?: return null
		return requestReleaseByTag(repository, tag)
	}

	private suspend fun requestLatestTag(repository: String): String? {
		return runCatchingCancellable {
			val request = Request.Builder()
				.get()
				.url("https://github.com/$repository/releases/latest")
				.build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return null
				val pathSegments = response.request.url.pathSegments
				val tagIndex = pathSegments.indexOf("tag")
				val tag = if (tagIndex >= 0) pathSegments.getOrNull(tagIndex + 1)
				else pathSegments.lastOrNull()
				tag?.takeIf { it.isNotBlank() }
			}
		}.getOrNull()
	}

	private suspend fun requestReleaseByTag(repository: String, tag: String): ExternalPluginDto? {
		return runCatchingCancellable {
			val (owner, repoName) = splitRepository(repository) ?: return null
			val url = HttpUrl.Builder()
				.scheme("https")
				.host("api.github.com")
				.addPathSegments("repos/$owner/$repoName/releases/tags/$tag")
				.build()
			val request = Request.Builder()
				.get().url(url)
				.build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return null
				val body = response.body.string()
				if (body.isBlank()) return null
				val json = JSONObject(body)
				val asset = findPluginAsset(json.optJSONArray("assets")) ?: return null
				ExternalPluginDto(repository, tag, asset.first, asset.second)
			}
		}.getOrNull()
	}

	private fun normalizeRepository(input: String): String? {
		val trimmed = input.trim().takeIf { it.isNotEmpty() } ?: return null
		return (GITHUB_URL_REGEX.matchEntire(trimmed) ?: REPOSITORY_REGEX.matchEntire(trimmed))
			?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
	}

	private fun splitRepository(repository: String): Pair<String, String>? {
		val parts = repository.split('/', limit = 2)
		if (parts.size < 2) return null
		val owner = parts[0].trim()
		val repo = parts[1].trim()
		if (owner.isBlank() || repo.isBlank()) return null
		return owner to repo
	}

	private fun findPluginAsset(assets: JSONArray?): Pair<String, String>? {
		assets ?: return null
		var fallbackApk: Pair<String, String>? = null
		for (i in 0 until assets.length()) {
			val asset = assets.optJSONObject(i) ?: continue
			val name = asset.optString("name")
			val url = asset.optString("browser_download_url")
			if (url.isBlank()) continue
			if (name.endsWith(".jar", ignoreCase = true)) {
				return name to url
			}
			if (fallbackApk == null && name.endsWith(".apk", ignoreCase = true)) {
				fallbackApk = name to url
			}
		}
		return fallbackApk
	}

	private fun readAndCleanupMeta(installedFiles: Set<String>): MutableMap<String, GithubMeta> {
		val meta = readMeta()
		if (meta.keys.retainAll(installedFiles)) {
			writeMeta(meta)
		}
		return meta
	}

	private fun saveGithubMeta(fileName: String, repository: String, tag: String) {
		updateMeta {
			it[fileName] = GithubMeta(repository = repository, tag = tag)
		}
	}

	private fun clearGithubMeta(fileName: String) {
		updateMeta {
			it.remove(fileName)
		}
	}

	private fun updateMeta(block: (MutableMap<String, GithubMeta>) -> Unit) {
		val meta = readMeta()
		block(meta)
		writeMeta(meta)
	}

	private fun readMeta(): MutableMap<String, GithubMeta> {
		val raw = prefs.getString(PREFS_KEY_GITHUB_META, null).orEmpty()
		if (raw.isBlank()) {
			return LinkedHashMap()
		}
		return runCatching {
			val json = JSONObject(raw)
			val out = LinkedHashMap<String, GithubMeta>(json.length())
			val keys = json.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val obj = json.optJSONObject(key) ?: continue
				val repository = obj.optString(JSON_KEY_REPOSITORY)
				val tag = obj.optString(JSON_KEY_TAG)
				if (repository.isNotBlank() && tag.isNotBlank()) {
					out[key] = GithubMeta(repository = repository, tag = tag)
				}
			}
			out
		}.getOrElse { LinkedHashMap() }
	}

	private fun writeMeta(meta: Map<String, GithubMeta>) {
		val json = JSONObject()
		meta.forEach { (fileName, value) ->
			json.put(
				fileName,
				JSONObject()
					.put(JSON_KEY_REPOSITORY, value.repository)
					.put(JSON_KEY_TAG, value.tag),
			)
		}
		prefs.edit {
			putString(PREFS_KEY_GITHUB_META, json.toString())
		}
	}

	private data class GithubMeta(
		val repository: String,
		val tag: String,
	)

	private fun String.pluginFileExtension(): String? {
		val lower = lowercase(Locale.ROOT)
		return when {
			lower.endsWith(".apk") -> ".apk"
			lower.endsWith(".jar") -> ".jar"
			else -> null
		}
	}

	private fun String.removePluginFileSuffix(): String {
		val dot = lastIndexOf('.')
		if (dot <= 0) return this
		return when (substring(dot).lowercase(Locale.ROOT)) {
			".jar", ".apk" -> substring(0, dot)
			else -> this
		}
	}

	private companion object {
		const val PREFS_NAME = "plugins_manage"
		const val PREFS_KEY_GITHUB_META = "github_meta"
		const val JSON_KEY_REPOSITORY = "repository"
		const val JSON_KEY_TAG = "tag"
		const val DEFAULT_PLUGIN_EXTENSION = ".jar"
		val REPOSITORY_REGEX = Regex("""^\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?\s*$""")
		val GITHUB_URL_REGEX = Regex(
			"""(?i)^\s*(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?\s*$""",
		)
	}
}
