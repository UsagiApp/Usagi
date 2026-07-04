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
class UpdatePluginsProvider @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
	private val database: MangaDatabase,
	private val savedFiltersRepository: SavedFiltersRepository,
	private val mangaDynamicRepository: MangaDynamicRepository,
	private val pluginKeyResolver: PluginKeyResolver,
) {
	private val mutex = Mutex()
	private val prefs by lazy {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	suspend fun runAutoUpdate(settings: AppSettings) {
		if (!settings.isAutoPluginsEnabled || !mutex.tryLock()) return
		try {
			withContext(Dispatchers.IO) {
				val now = System.currentTimeMillis()
				if (now - settings.lastAutoPlugins < COOLDOWN) return@withContext
				settings.lastAutoPlugins = now
				val installed = mangaDynamicRepository.get().toSet()
				if (installed.isEmpty()) return@withContext
				val meta = readAndCleanDto(installed)
				if (meta.isEmpty()) return@withContext
				val pluginsDir = mangaDynamicRepository.getDir()
				val results = installed.map { name ->
					async {
						val info = meta[name] ?: return@async null
						val release = requestRelease(info.repository, name) ?: return@async null
						if (release.tag == info.tag) return@async null
						if (replacePlugin(release.downloadUrl, File(pluginsDir, name))) {
							name to RemoteReleaseDto(info.repository, release.tag)
						} else null
					}
				}.awaitAll().filterNotNull()

				if (results.isNotEmpty()) {
					results.forEach { (name, dto) -> meta[name] = dto }
					writeDto(meta)
					reloadPlugins(pluginsDir)
				}
			}
		} finally {
			mutex.unlock()
		}
	}

	suspend fun installPlugin(release: ExternalPluginDto, fileName: String): Boolean =
		withContext(Dispatchers.Default) {
			runCatchingCancellable {
				val pluginsDir = mangaDynamicRepository.getDir()
				val outFile = File(pluginsDir, fileName)
				val ok = replacePlugin(release.downloadUrl, outFile)
				if (!ok) throw IOException()
				saveDto(fileName, release.repository, release.tag)
				reloadPlugins(pluginsDir)
			}.isSuccess
		}

	private suspend fun reloadPlugins(pluginsDir: File) {
		mangaDynamicRepository.load(pluginsDir)
		pluginKeyResolver.normalize(database, savedFiltersRepository)
	}

	suspend fun requestRelease(repository: String, name: String? = null): ExternalPluginDto? {
		val tag = requestTag(repository) ?: return null
		val releases = requestPlugins(repository, tag)
		if (releases.isEmpty()) return null
		if (name != null) releases.find { it.fileName == name }?.let { return it }
		return releases.firstOrNull()
	}

	suspend fun requestTag(repository: String): String? {
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

	suspend fun requestPlugins(repository: String, tag: String): List<ExternalPluginDto> {
		return runCatchingCancellable {
			val (owner, repoName) = splitRepository(repository) ?: return emptyList()
			val url = HttpUrl.Builder()
				.scheme("https")
				.host("api.github.com")
				.addPathSegments("repos/$owner/$repoName/releases/tags/$tag")
				.build()
			val request = Request.Builder()
				.get().url(url)
				.build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return emptyList()
				val body = response.body.string()
				if (body.isBlank()) return emptyList()
				val json = JSONObject(body)
				val assets = find(json.optJSONArray("assets"))
				assets.map { ExternalPluginDto(repository, tag, it.first, it.second) }
			}
		}.getOrDefault(emptyList())
	}

	fun resolve(input: String): String? {
		val trimmed = input.trim().takeIf { it.isNotEmpty() } ?: return null
		return (GITHUB_URL_REGEX.matchEntire(trimmed) ?: REPOSITORY_REGEX.matchEntire(trimmed))
			?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
	}

	fun splitRepository(repository: String): Pair<String, String>? {
		val parts = repository.split('/', limit = 2)
		if (parts.size < 2) return null
		val owner = parts[0].trim()
		val repo = parts[1].trim()
		if (owner.isBlank() || repo.isBlank()) return null
		return owner to repo
	}

	fun find(assets: JSONArray?): List<Pair<String, String>> {
		assets ?: return emptyList()
		val list = mutableListOf<Pair<String, String>>()
		for (i in 0 until assets.length()) {
			val asset = assets.optJSONObject(i) ?: continue
			val name = asset.optString("name")
			val url = asset.optString("browser_download_url")
			if (name.endsWith(".jar", true) && url.isNotBlank()) {
				list.add(name to url)
			}
		}
		return list
	}

	suspend fun replacePlugin(url: String, dest: File): Boolean {
		return runCatchingCancellable {
			val request = Request.Builder().get().url(url).build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) throw IOException()
				PluginFileLoader.copyFromStream(dest, response.body.byteStream())
			}
		}.isSuccess
	}

	fun readAndCleanDto(installedFiles: Set<String>): MutableMap<String, RemoteReleaseDto> {
		val meta = readDto()
		if (meta.keys.retainAll(installedFiles)) {
			writeDto(meta)
		}
		return meta
	}

	fun saveDto(fileName: String, repository: String, tag: String) {
		updateDto {
			it[fileName] = RemoteReleaseDto(repository, tag)
		}
	}

	fun clearDto(fileName: String) {
		updateDto {
			it.remove(fileName)
		}
	}

	fun renameDto(oldName: String, newName: String) {
		updateDto {
			val value = it.remove(oldName)
			if (value != null) {
				it[newName] = value
			}
		}
	}

	private fun updateDto(block: (MutableMap<String, RemoteReleaseDto>) -> Unit) {
		val meta = readDto()
		block(meta)
		writeDto(meta)
	}

	fun readDto(): MutableMap<String, RemoteReleaseDto> {
		val raw = prefs.getString(PREFS_KEY, null).orEmpty()
		if (raw.isBlank()) {
			return LinkedHashMap()
		}
		return runCatching {
			val json = JSONObject(raw)
			val out = LinkedHashMap<String, RemoteReleaseDto>(json.length())
			val keys = json.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val obj = json.optJSONObject(key) ?: continue
				val repository = obj.optString(KEY_REPOSITORY)
				val tag = obj.optString(KEY_TAG)
				if (repository.isNotBlank() && tag.isNotBlank()) {
					out[key] = RemoteReleaseDto(repository, tag)
				}
			}
			out
		}.getOrElse { LinkedHashMap() }
	}

	fun writeDto(meta: Map<String, RemoteReleaseDto>) {
		val json = JSONObject()
		meta.forEach { (fileName, value) ->
			json.put(
				fileName,
				JSONObject()
					.put(KEY_REPOSITORY, value.repository)
					.put(KEY_TAG, value.tag),
			)
		}
		prefs.edit {
			putString(PREFS_KEY, json.toString())
		}
	}

	companion object {
		private const val PREFS_NAME = "plugins_manage"
		private const val PREFS_KEY = "github_meta"
		private const val KEY_REPOSITORY = "repository"
		private const val KEY_TAG = "tag"
		private const val COOLDOWN = 600000L // 10m
		val REPOSITORY_REGEX = Regex("""^\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?\s*$""")
		val GITHUB_URL_REGEX = Regex(
			"""(?i)^\s*(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?\s*$""",
		)
	}
}
