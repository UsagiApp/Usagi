package org.draken.usagi.settings.sources.manage.plugins

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.tsukimix.core.parser.external.ExtensionProvider
import org.draken.tsukimix.core.parser.external.NativeExtManager
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.json.JSONArray
import org.json.JSONObject
import tsuki.util.await
import tsuki.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdatePluginsProvider
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		@BaseHttpClient private val okHttpClient: OkHttpClient,
		private val database: MangaDatabase,
		private val savedFiltersRepository: SavedFiltersRepository,
		private val mangaDynamicRepository: MangaDynamicRepository,
		private val pluginKeyResolver: PluginKeyResolver,
		private val manager: NativeExtManager,
		private val provider: ExtensionProvider,
	) {
		private val mutex = Mutex()
		private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

		suspend fun runAutoUpdate(settings: AppSettings) {
			if (!settings.isAutoPluginsEnabled || !mutex.tryLock()) return
			try {
				withContext(Dispatchers.IO) {
					val now = System.currentTimeMillis()
					if (now - settings.lastAutoPlugins < COOLDOWN) return@withContext
					settings.lastAutoPlugins = now
					val installed = mangaDynamicRepository.get().toSet()
					if (installed.isNotEmpty()) {
						val meta = readAndCleanDto(installed)
						if (meta.isNotEmpty()) {
							val pluginsDir = mangaDynamicRepository.getDir()
							val results =
								installed
									.map { name ->
										async {
											val info = meta[name] ?: return@async null
											val rel = requestRelease(info.repository, name) ?: return@async null
											if (rel.tag == info.tag) return@async null
											if (replacePlugin(rel.downloadUrl, File(pluginsDir, name))) {
												name to RemoteReleaseDto(info.repository, rel.tag)
											} else {
												null
											}
										}
									}.awaitAll()
									.filterNotNull()
							if (results.isNotEmpty()) {
								results.forEach { (name, dto) -> meta[name] = dto }
								writeDto(meta)
								reloadPlugins(pluginsDir)
							}
						}
					}
					val inst = manager.installed.value.associateBy { it.packageName }
					provider
						.loadSaved()
						.filter { art ->
							val cur = inst[art.packageName]
							cur != null && (art.versionCode ?: 0) > cur.versionCode
						}.map { art -> async { runCatching { manager.install(art) } } }
						.awaitAll()
				}
			} finally {
				mutex.unlock()
			}
		}

		suspend fun installPlugin(
			release: ExternalPluginDto,
			fileName: String,
		): Boolean =
			withContext(Dispatchers.Default) {
				runCatchingCancellable {
					val dir = mangaDynamicRepository.getDir()
					if (!replacePlugin(release.downloadUrl, File(dir, fileName))) throw IOException()
					saveDto(fileName, release.repository, release.tag)
					reloadPlugins(dir)
				}.isSuccess
			}

		private suspend fun reloadPlugins(pluginsDir: File) {
			mangaDynamicRepository.load(pluginsDir)
			pluginKeyResolver.normalize(database, savedFiltersRepository)
		}

		suspend fun requestRelease(
			repository: String,
			name: String? = null,
		): ExternalPluginDto? {
			val tag = requestTag(repository) ?: return null
			val releases = requestPlugins(repository, tag)
			if (name != null) releases.find { it.fileName == name }?.let { return it }
			return releases.firstOrNull()
		}

		suspend fun requestTag(repository: String): String? =
			runCatchingCancellable {
				val req =
					Request
						.Builder()
						.get()
						.url("https://github.com/$repository/releases/latest")
						.build()
				okHttpClient.newCall(req).await().use { resp ->
					if (!resp.isSuccessful) return null
					val segs = resp.request.url.pathSegments
					val idx = segs.indexOf("tag")
					(if (idx >= 0) segs.getOrNull(idx + 1) else segs.lastOrNull())?.takeIf { it.isNotBlank() }
				}
			}.getOrNull()

		suspend fun requestPlugins(
			repository: String,
			tag: String,
		): List<ExternalPluginDto> =
			runCatchingCancellable {
				val (owner, repo) = splitRepository(repository) ?: return emptyList()
				val url =
					HttpUrl
						.Builder()
						.scheme("https")
						.host("api.github.com")
						.addPathSegments("repos/$owner/$repo/releases/tags/$tag")
						.build()
				val req =
					Request
						.Builder()
						.get()
						.url(url)
						.build()
				okHttpClient.newCall(req).await().use { resp ->
					if (!resp.isSuccessful) return emptyList()
					val body = resp.body.string()
					if (body.isBlank()) return emptyList()
					find(JSONObject(body).optJSONArray("assets")).map { ExternalPluginDto(repository, tag, it.first, it.second) }
				}
			}.getOrDefault(emptyList())

		suspend fun importFromUrl(url: String): Boolean {
			val trimmed = url.trim()
			val match = DOWNLOAD_URL_REGEX.matchEntire(trimmed)
			if (match != null) {
				val (owner, repo, tag, fileName) = match.destructured
				if (owner.isNotBlank() && repo.isNotBlank() && tag.isNotBlank() && fileName.isNotBlank()) {
					return installPlugin(ExternalPluginDto("$owner/$repo", tag, fileName, trimmed), fileName)
				}
			}
			if (trimmed.endsWith(".jar", true) || trimmed.contains(".jar?", true)) {
				val rawName = trimmed.substringBefore('?').substringAfterLast('/')
				val safeName = PluginFileLoader.resolve(rawName)
				val dest = File(mangaDynamicRepository.getDir(), safeName)
				return runCatchingCancellable {
					val req =
						Request
							.Builder()
							.url(trimmed)
							.header("User-Agent", "Usagi-PluginDownloader/1.0")
							.build()
					okHttpClient.newCall(req).await().use { resp ->
						if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
						PluginFileLoader.copyFromStream(dest, resp.body.byteStream())
					}
					reloadPlugins(mangaDynamicRepository.getDir())
					true
				}.getOrDefault(false)
			}
			return false
		}

		fun resolve(input: String): String? {
			val trimmed = input.trim().takeIf { it.isNotEmpty() } ?: return null
			val match = GITHUB_URL_REGEX.matchEntire(trimmed) ?: REPOSITORY_REGEX.matchEntire(trimmed)
			return match?.groupValues?.takeIf { it.size >= 3 }?.let { "${it[1]}/${it[2]}" }
		}

		fun splitRepository(repository: String): Pair<String, String>? {
			val parts = repository.split('/', limit = 2)
			if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
			return parts[0].trim() to parts[1].trim()
		}

		fun find(assets: JSONArray?): List<Pair<String, String>> {
			assets ?: return emptyList()
			val list = mutableListOf<Pair<String, String>>()
			for (i in 0 until assets.length()) {
				val asset = assets.optJSONObject(i) ?: continue
				val name = asset.optString("name")
				val url = asset.optString("browser_download_url")
				if (name.endsWith(".jar", true) && url.isNotBlank()) list.add(name to url)
			}
			return list
		}

		suspend fun replacePlugin(
			url: String,
			dest: File,
		): Boolean =
			runCatchingCancellable {
				val req =
					Request
						.Builder()
						.get()
						.url(url)
						.build()
				okHttpClient.newCall(req).await().use { resp ->
					if (!resp.isSuccessful) throw IOException()
					PluginFileLoader.copyFromStream(dest, resp.body.byteStream())
				}
			}.isSuccess

		fun readAndCleanDto(installedFiles: Set<String>): MutableMap<String, RemoteReleaseDto> {
			val meta = readDto()
			if (meta.keys.retainAll(installedFiles)) writeDto(meta)
			return meta
		}

		fun saveDto(
			fileName: String,
			repository: String,
			tag: String,
		) {
			updateDto { it[fileName] = RemoteReleaseDto(repository, tag) }
		}

		fun clearDto(fileName: String) {
			updateDto { it.remove(fileName) }
		}

		fun renameDto(
			oldName: String,
			newName: String,
		) {
			updateDto { it.remove(oldName)?.let { v -> it[newName] = v } }
		}

		private fun updateDto(block: (MutableMap<String, RemoteReleaseDto>) -> Unit) {
			val meta = readDto()
			block(meta)
			writeDto(meta)
		}

		fun readDto(): MutableMap<String, RemoteReleaseDto> {
			val raw = prefs.getString(PREFS_KEY, null).orEmpty()
			if (raw.isBlank()) return LinkedHashMap()
			return runCatching {
				val json = JSONObject(raw)
				val out = LinkedHashMap<String, RemoteReleaseDto>(json.length())
				val keys = json.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					val obj = json.optJSONObject(key) ?: continue
					val repo = obj.optString(KEY_REPOSITORY)
					val tag = obj.optString(KEY_TAG)
					if (repo.isNotBlank() && tag.isNotBlank()) out[key] = RemoteReleaseDto(repo, tag)
				}
				out
			}.getOrElse { LinkedHashMap() }
		}

		fun writeDto(meta: Map<String, RemoteReleaseDto>) {
			val json = JSONObject()
			meta.forEach { (fileName, value) ->
				json.put(fileName, JSONObject().put(KEY_REPOSITORY, value.repository).put(KEY_TAG, value.tag))
			}
			prefs.edit { putString(PREFS_KEY, json.toString()) }
		}

		companion object {
			private const val PREFS_NAME = "plugins_manage"
			private const val PREFS_KEY = "github_meta"
			private const val KEY_REPOSITORY = "repository"
			private const val KEY_TAG = "tag"
			private const val COOLDOWN = 600000L // 10m
			val REPOSITORY_REGEX = Regex("""^\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?\s*$""")
			val GITHUB_URL_REGEX =
				Regex(
					"""(?i)^\s*(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?\s*$""",
				)
			private val DOWNLOAD_URL_REGEX =
				Regex(
					"""https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+\.jar)""",
					RegexOption.IGNORE_CASE,
				)
		}
	}
