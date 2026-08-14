package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.ConfigurationCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionManager
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiInjektBridge
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiLoadResult
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import org.draken.usagi.core.network.BaseHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class DirectTachiyomiExtensionManager
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		@BaseHttpClient private val httpClient: OkHttpClient,
		private val injektBridge: TachiyomiInjektBridge,
	) {
		private val refreshMutex = Mutex()
		private val installMutex = Mutex()
		private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
		private val sourceByName = ConcurrentHashMap<String, TachiyomiMangaSource>()
		private val sourceById = ConcurrentHashMap<Long, TachiyomiMangaSource>()
		private val resolver = DirectLanguageResolver(context)
		private val artifactClient by lazy {
			httpClient
				.newBuilder()
				.apply {
					interceptors().clear()
					networkInterceptors().clear()
					cache(null)
					retryOnConnectionFailure(true)
				}.build()
		}
		private val directory = File(context.filesDir, DIRECT_DIR).also { it.mkdirs() }

		private val dexDirectory = File(context.codeCacheDir, DEX_DIR).also { it.mkdirs() }
		private val metadataFile = File(directory, METADATA_FILE)

		private val _sources = MutableStateFlow<List<TachiyomiMangaSource>>(emptyList())
		private val _installed = MutableStateFlow<List<DirectTachiyomiInstalled>>(emptyList())
		private val _failed = MutableStateFlow<List<DirectTachiyomiFailure>>(emptyList())
		private var ready = false

		val sources: StateFlow<List<TachiyomiMangaSource>> = _sources
		val installed: StateFlow<List<DirectTachiyomiInstalled>> = _installed
		val failed: StateFlow<List<DirectTachiyomiFailure>> = _failed

		@Volatile
		var lastInstallError: String? = null
			private set

		init {
			activeInstance = this
		}

		suspend fun ensureReady(forceRefresh: Boolean = false) {
			if (!forceRefresh && ready) return
			refreshMutex.withLock {
				if (!forceRefresh && ready) return@withLock
				reload()
				ready = true
			}
		}

		suspend fun install(artifact: TachiyomiExtensionArtifact): Boolean =
			installMutex.withLock {
				withContext(Dispatchers.IO) {
					val packageName = artifact.packageName.trim().takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
					val staging = File(directory, "$packageName.staging.apk")

					val downloaded = File(directory, "$packageName.download")
					val destination = File(directory, "$packageName.apk")
					val backup = File(directory, "$packageName.backup")
					val candidates = listOfNotNull(artifact.apkUrl, artifact.jarUrl).distinct()
					if (candidates.isEmpty()) {
						lastInstallError = "Catalog entry has no APK artifact URL"
						return@withContext false
					}

					val errors = ArrayList<String>(candidates.size)
					var loaded: TachiyomiLoadResult.Success? = null
					var selectedUrl: String? = null
					for (url in candidates) {
						downloaded.delete()
						staging.delete()
						val downloadError = download(url, downloaded)
						if (downloadError != null) {
							errors += "$url → $downloadError"
							continue
						}
						if (!prepareDexArtifact(downloaded, staging)) {
							errors += "$url → Download is not an Android APK with DEX code"
							continue
						}
						if (!makeReadOnly(staging)) {
							errors += "$url → Cannot make staged APK read-only"
							staging.delete()
							continue
						}
						val result = loadArtifact(staging, artifact)

						if (result is TachiyomiLoadResult.Success) {
							loaded = result
							selectedUrl = url
							break
						}
						errors += "$url → ${(result as TachiyomiLoadResult.Error).message}"
					}
					downloaded.delete()
					if (loaded == null || selectedUrl == null) {
						staging.delete()
						lastInstallError = errors.takeLast(2).joinToString("\n").ifBlank { "No compatible extension artifact could be loaded" }
						return@withContext false
					}

					if (backup.exists()) backup.delete()
					if (destination.exists() && !destination.renameTo(backup)) {
						staging.delete()
						return@withContext false
					}
					if (!staging.renameTo(destination)) {
						staging.delete()
						backup.renameTo(destination)
						return@withContext false
					}

					val record =
						DirectTachiyomiInstalled(
							packageName = packageName,
							name = artifact.name,
							repositoryUrl = artifact.repositoryUrl,
							artifactUrl = selectedUrl,
							jarUrl = artifact.jarUrl,
							apkUrl = artifact.apkUrl,
							versionCode = loaded.versionCode,
							versionName = loaded.versionName,
							libVersion = loaded.libVersion,
							contentRating = loaded.isNsfw.let { if (it) TachiyomiContentRating.NSFW else artifact.contentRating },
							sourceCount = loaded.catalogueSources.size,
							iconUrl = artifact.iconUrl,
							sources = artifact.sources,
						)
					writeRecords((readRecords().filterNot { it.packageName == packageName } + record))
					backup.delete()
					reload()
					lastInstallError = null
					true
				}
			}

		suspend fun remove(packageName: String): Boolean =
			installMutex.withLock {
				withContext(Dispatchers.IO) {
					val safe = packageName.takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
					val destination = File(directory, "$safe.apk")
					val removed = !destination.exists() || destination.delete()
					if (removed) {
						writeRecords(readRecords().filterNot { it.packageName == safe })
						reload()
					}
					removed
				}
			}

		fun getActiveSources(): List<TachiyomiMangaSource> = resolver.selectActive(_sources.value)

		fun owns(source: TachiyomiMangaSource): Boolean = source.pkgName in _installed.value.map { it.packageName }.toSet()

		fun getSourceByName(name: String): TachiyomiMangaSource? = sourceByName[name]

		fun getSourceById(id: Long): TachiyomiMangaSource? = sourceById[id]

		fun resolve(source: TachiyomiMangaSource): TachiyomiMangaSource = resolver.resolve(source, _sources.value)

		fun getLanguage(source: TachiyomiMangaSource): List<TachiyomiMangaSource> = resolver.getVariants(source, _sources.value)

		fun getActiveLanguage(source: TachiyomiMangaSource): String? = resolver.getActiveLanguage(source, _sources.value)

		fun setActiveLanguage(
			source: TachiyomiMangaSource,
			language: String,
		) = resolver.setActiveLanguage(source, language)

		fun addLangToPref(
			screen: PreferenceScreen,
			source: TachiyomiMangaSource,
			title: CharSequence,
			onChanged: () -> Unit,
		) {
			val variants = getLanguage(source).distinctBy { it.locale.lowercase(Locale.ROOT) }.sortedBy { it.languageDisplayName }
			if (variants.size <= 1) return
			ListPreference(screen.context).apply {
				key = "language"
				order = 1
				isPersistent = false
				isIconSpaceReserved = false
				entries = variants.map { it.languageDisplayName }.toTypedArray()
				entryValues = variants.map { it.locale }.toTypedArray()
				value = getActiveLanguage(source) ?: variants.first().locale
				summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
				this.title = title
				dialogTitle = title
				setOnPreferenceChangeListener { _, newValue ->
					val language = newValue as? String ?: return@setOnPreferenceChangeListener false
					setActiveLanguage(source, language)
					onChanged()
					true
				}
				screen.addPreference(this)
			}
		}

		private suspend fun reload() {
			injektBridge.initialize()
			val records = readRecords()
			val successful = ArrayList<DirectTachiyomiInstalled>(records.size)
			val failures = ArrayList<DirectTachiyomiFailure>()
			val sources = ArrayList<TachiyomiMangaSource>()
			sourceByName.clear()
			sourceById.clear()
			classLoaders.clear()
			for (record in records) {
				val file = File(directory, "${record.packageName}.apk")
				if (!file.exists()) {
					failures += DirectTachiyomiFailure(record.packageName, "Artifact not found")
					continue
				}
				if (!makeReadOnly(file)) {
					failures += DirectTachiyomiFailure(record.packageName, "Artifact is writable and could not be made read-only")
					continue
				}
				val result = loadArtifact(file, record.toArtifact())

				if (result is TachiyomiLoadResult.Success) {
					val updated =
						record.copy(
							versionCode = result.versionCode,
							versionName = result.versionName,
							libVersion = result.libVersion,
							contentRating = if (result.isNsfw) TachiyomiContentRating.NSFW else record.contentRating,
							sourceCount = result.catalogueSources.size,
							iconUrl = record.iconUrl,
							sources = if (record.sources.isNotEmpty()) record.sources else record.toArtifact().sources,
						)
					successful += updated
					result.catalogueSources.forEach { source ->
						val wrapped = TachiyomiMangaSource(source, updated.packageName, updated.isNsfw, hasLanguageSuffix = false)
						sources += wrapped
						sourceById[wrapped.sourceId] = wrapped
						sourceByName[wrapped.name] = wrapped
					}
				} else {
					failures += DirectTachiyomiFailure(record.packageName, (result as? TachiyomiLoadResult.Error)?.message ?: "Extension load failed")
				}
			}
			val withSuffix = sources.groupingBy { it.pkgName to it.displayName }.eachCount()
			val normalized = sources.map { it.copy(hasLanguageSuffix = (withSuffix[it.pkgName to it.displayName] ?: 0) > 1) }
			sourceByName.clear()
			sourceById.clear()
			normalized.forEach {
				sourceByName[it.name] = it
				sourceById[it.sourceId] = it
			}
			_sources.value = normalized
			_installed.value = successful
			_failed.value = failures
		}

		private fun loadArtifact(
			file: File,
			artifact: TachiyomiExtensionArtifact,
		): TachiyomiLoadResult {
			val packageInfo = getPackageInfo(file)
			val packageName = packageInfo?.packageName?.takeIf { PACKAGE_REGEX.matches(it) } ?: artifact.packageName
			val metadata = packageInfo?.applicationInfo?.metaData ?: catalogMetadata(packageName, artifact)

			val versionName = packageInfo?.versionName ?: artifact.versionName ?: "0.0.0"
			val libVersion =
				readLibVersion(metadata, versionName) ?: artifact.extensionLib
					?: return TachiyomiLoadResult.Error(artifact.packageName, "Missing extension library version")
			if (libVersion !in LIB_VERSION_MIN..LIB_VERSION_MAX) {
				return TachiyomiLoadResult.Error(artifact.packageName, "Incompatible extension library: $libVersion")
			}
			val sourceClassNames =
				metadata.getString(METADATA_SOURCE_CLASS)
					?: metadata.getString(METADATA_SOURCE_FACTORY)
					?: return TachiyomiLoadResult.Error(artifact.packageName, "Missing source class metadata")
			val manifestRating = TachiyomiContentRating.fromManifest(metadata, libVersion)
			val effectiveRating = if (manifestRating != TachiyomiContentRating.UNSPECIFIED) manifestRating else artifact.contentRating
			val loader =
				runCatching {
					val optimizedDirectory =
						File(dexDirectory, artifact.packageName).also { directory ->
							if (!directory.exists() && !directory.mkdirs()) {
								error("Cannot create optimized DEX directory: ${directory.absolutePath}")
							}
							if (!directory.isDirectory || !directory.canWrite()) {
								error("Optimized DEX directory is not writable: ${directory.absolutePath}")
							}
						}
					DirectDexClassLoader(
						file.absolutePath,
						optimizedDirectory.absolutePath,
						librarySearchPath = null,
						parent = context.classLoader,
					)
				}.getOrElse {
					return TachiyomiLoadResult.Error(
						artifact.packageName,
						"Cannot create extension classloader: ${it.describeFailure()}",
						it,
					)
				}

			return runCatching {
				val sources = loadSources(packageName, sourceClassNames, loader)

				if (sources.isEmpty()) error("No sources loaded")
				classLoaders[artifact.packageName] = loader
				TachiyomiLoadResult.Success(
					pkgName = artifact.packageName,
					appName = artifact.name,
					versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: artifact.versionCode ?: 0L,
					versionName = versionName,
					libVersion = libVersion,
					lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.distinct().let { if (it.size == 1) it.first() else "all" },
					isNsfw = effectiveRating.isNsfw,
					sources = sources,
				)
			}.getOrElse { TachiyomiLoadResult.Error(artifact.packageName, "Failed to load extension: ${it.describeFailure()}", it) }
		}

		private fun Throwable.describeFailure(): String =
			generateSequence(this) { it.cause }
				.mapNotNull { error -> error.message?.takeIf { it.isNotBlank() } }
				.distinct()
				.joinToString(" <- ")
				.ifBlank { javaClass.simpleName }

		private fun loadSources(
			packageName: String,
			classNames: String,
			loader: ClassLoader,
		): List<Source> =
			classNames.split(';', ':', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith('.')) packageName + it else it }.flatMap { className ->
				when (val instance = loader.loadClass(className).getDeclaredConstructor().newInstance()) {
					is Source -> listOf(instance)
					is SourceFactory -> instance.createSources()
					else -> error("Unknown source class type: ${instance.javaClass.name}")
				}
			}

		private fun getPackageInfo(file: File): PackageInfo? =
			runCatching {
				@Suppress("DEPRECATION")
				context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
			}.getOrNull()

		private fun catalogMetadata(
			packageName: String,
			artifact: TachiyomiExtensionArtifact,
		): Bundle =
			Bundle().apply {
				putString(METADATA_SOURCE_CLASS, "$packageName.ExtensionGenerated")
				artifact.extensionLib?.let { putDouble(METADATA_EXTENSION_LIB, it) }
				when (artifact.contentRating) {
					TachiyomiContentRating.SAFE -> putInt(METADATA_CONTENT_WARNING, 0)
					TachiyomiContentRating.MIXED -> putInt(METADATA_CONTENT_WARNING, 1)
					TachiyomiContentRating.NSFW -> putInt(METADATA_CONTENT_WARNING, 2)
					TachiyomiContentRating.UNSPECIFIED -> Unit
				}
			}

		private fun download(
			url: String,
			destination: File,
		): String? =
			runCatching {
				val request =
					Request
						.Builder()
						.url(url)
						.header("User-Agent", "Usagi-TachiyomiExtension/1.0")
						.get()
						.build()
				artifactClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) error("HTTP ${response.code} ${response.message}")
					val body = response.body ?: error("Empty response body")
					body.byteStream().use { input ->
						destination.outputStream().use { output -> input.copyTo(output) }
					}
				}
				null
			}.getOrElse { error -> error.message ?: error.javaClass.simpleName }

		private fun prepareDexArtifact(
			input: File,
			output: File,
		): Boolean =
			runCatching {
				output.setWritable(true, false)
				output.delete()
				ZipFile(input).use { archive ->
					val manifest = archive.getEntry("AndroidManifest.xml") ?: return@use false
					val hasDex = archive.entries().asSequence().any { entry -> entry.name.matches(Regex("classes(\\d*)?\\.dex")) }
					if (hasDex) {
						input.copyTo(output, overwrite = true)
						return@use true
					}
					val nestedApk = archive.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
					if (nestedApk != null) {
						archive.getInputStream(nestedApk).use { source -> output.outputStream().use { target -> source.copyTo(target) } }
						return@use ZipFile(output).use { nested ->
							nested.getEntry("AndroidManifest.xml") != null && nested.entries().asSequence().any { it.name.matches(Regex("classes(\\d*)?\\.dex")) }
						}
					}
					false
				}
			}.getOrDefault(false)

		private fun makeReadOnly(file: File): Boolean {
			if (!file.exists() || !file.isFile) return false
			file.setReadable(true, false)
			file.setWritable(false, false)
			return !file.canWrite()
		}

		private fun readRecords(): List<DirectTachiyomiInstalled> {
			val array = runCatching { JSONArray(metadataFile.takeIf { it.exists() }?.readText().orEmpty()) }.getOrNull() ?: return emptyList()
			return buildList {
				for (index in 0 until array.length()) {
					val obj = array.optJSONObject(index) ?: continue
					DirectTachiyomiInstalled.fromJson(obj)?.let(::add)
				}
			}
		}

		private fun writeRecords(records: List<DirectTachiyomiInstalled>) {
			val array = JSONArray()
			records.distinctBy { it.packageName }.forEach { array.put(it.toJson()) }
			metadataFile.writeText(array.toString())
		}

		private fun readLibVersion(
			metadata: Bundle,
			versionName: String,
		): Double? {
			val raw = runCatching { metadata.get(METADATA_EXTENSION_LIB) }.getOrNull()
			val parsed =
				when (raw) {
					is Number -> raw.toDouble()
					is String -> raw.toDoubleOrNull()
					else -> versionName.substringBeforeLast('.').toDoubleOrNull()
				}
			return normalizeLibVersion(parsed)
		}

		private fun normalizeLibVersion(value: Double?): Double? =
			value?.let {
				when {
					abs(it - 1.4) < 0.01 -> 1.4
					abs(it - 1.6) < 0.01 -> 1.6
					else -> it
				}
			}

		companion object {
			private const val DIRECT_DIR = "tachiyomi-direct"
			private const val DEX_DIR = "tachiyomi-direct-dex"
			private const val METADATA_FILE = "installed.json"

			private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
			private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
			private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
			private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
			private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
			private const val LIB_VERSION_MIN = 1.4
			private const val LIB_VERSION_MAX = 1.6
			private const val PACKAGE_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
			private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

			@Volatile private var activeInstance: DirectTachiyomiExtensionManager? = null

			fun getByName(name: String): TachiyomiMangaSource? = activeInstance?.getSourceByName(name)
		}
	}

enum class TachiyomiContentRating {
	UNSPECIFIED,
	SAFE,
	MIXED,
	NSFW,
	;

	val isNsfw: Boolean
		get() = this == NSFW

	companion object {
		fun fromCatalog(
			raw: String?,
			extensionLib: Double?,
		): TachiyomiContentRating {
			val value = raw?.trim().orEmpty()
			return when {
				value.equals("CONTENT_WARNING_NSFW", true) || value.equals("NSFW", true) -> {
					NSFW
				}

				value.equals("CONTENT_WARNING_MIXED", true) || value.equals("MIXED", true) -> {
					MIXED
				}

				value.equals("CONTENT_WARNING_SAFE", true) || value.equals("SAFE", true) -> {
					SAFE
				}

				value.equals("CONTENT_WARNING_UNSPECIFIED", true) || value.equals("UNSPECIFIED", true) -> {
					UNSPECIFIED
				}

				else -> {
					val number = value.toIntOrNull() ?: return UNSPECIFIED
					if ((extensionLib ?: 1.6) >= 1.6) {
						when (number) {
							1 -> SAFE
							2 -> MIXED
							3 -> NSFW
							else -> UNSPECIFIED
						}
					} else {
						when (number) {
							0 -> SAFE
							1 -> MIXED
							2 -> NSFW
							else -> UNSPECIFIED
						}
					}
				}
			}
		}

		fun fromManifest(
			metadata: Bundle,
			extensionLib: Double?,
		): TachiyomiContentRating {
			val warning = runCatching { metadata.getInt("tachiyomix.contentWarning", Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
			val legacyNsfw = runCatching { metadata.getInt("tachiyomi.extension.nsfw", 0) }.getOrDefault(0)
			return when {
				warning == 0 -> SAFE
				warning == 1 -> MIXED
				warning == 2 -> NSFW
				legacyNsfw == 1 -> NSFW
				else -> UNSPECIFIED
			}
		}
	}
}

data class TachiyomiCatalogSource(
	val id: Long,
	val name: String,
	val language: String,
	val homeUrl: String?,
	val contentRating: TachiyomiContentRating = TachiyomiContentRating.UNSPECIFIED,
)

data class TachiyomiExtensionArtifact(
	val repositoryUrl: String,
	val name: String,
	val packageName: String,
	val jarUrl: String?,
	val apkUrl: String?,
	val iconUrl: String?,
	val extensionLib: Double?,
	val versionCode: Long?,
	val versionName: String?,
	val contentRating: TachiyomiContentRating = TachiyomiContentRating.UNSPECIFIED,
	val sourceCount: Int = 0,
	val sources: List<TachiyomiCatalogSource> = emptyList(),
) {
	val isNsfw: Boolean get() = contentRating.isNsfw
}

data class DirectTachiyomiInstalled(
	val packageName: String,
	val name: String,
	val repositoryUrl: String,
	val artifactUrl: String,
	val jarUrl: String?,
	val apkUrl: String?,
	val iconUrl: String?,
	val versionCode: Long,
	val versionName: String,
	val libVersion: Double,
	val contentRating: TachiyomiContentRating,
	val sourceCount: Int,
	val sources: List<TachiyomiCatalogSource> = emptyList(),
) {
	val isNsfw: Boolean get() = contentRating.isNsfw

	fun toArtifact() = TachiyomiExtensionArtifact(repositoryUrl, name, packageName, jarUrl, apkUrl, iconUrl, libVersion, versionCode, versionName, contentRating, sourceCount, sources)

	fun toJson() =
		JSONObject().apply {
			put("packageName", packageName)
			put("name", name)
			put("repositoryUrl", repositoryUrl)
			put("artifactUrl", artifactUrl)
			put("jarUrl", jarUrl)
			put("apkUrl", apkUrl)
			put("iconUrl", iconUrl)
			put("versionCode", versionCode)
			put("versionName", versionName)
			put("libVersion", libVersion)
			put("contentRating", contentRating.name)
			put("isNsfw", isNsfw)
			put("sourceCount", sourceCount)
			put(
				"sources",
				JSONArray().apply {
					sources.forEach { source ->
						put(
							JSONObject().apply {
								put("id", source.id)
								put("name", source.name)
								put("language", source.language)
								put("homeUrl", source.homeUrl)
								put("contentRating", source.contentRating.name)
							},
						)
					}
				},
			)
		}

	companion object {
		fun fromJson(obj: JSONObject) =
			runCatching {
				DirectTachiyomiInstalled(
					packageName = obj.getString("packageName"),
					name = obj.optString("name", obj.getString("packageName")),
					repositoryUrl = obj.optString("repositoryUrl"),
					artifactUrl = obj.getString("artifactUrl"),
					jarUrl = obj.optString("jarUrl").takeIf { it.isNotBlank() },
					apkUrl = obj.optString("apkUrl").takeIf { it.isNotBlank() },
					iconUrl = obj.optString("iconUrl").takeIf { it.isNotBlank() },
					versionCode = obj.optLong("versionCode", 0L),
					versionName = obj.optString("versionName", "0.0.0"),
					libVersion = obj.optDouble("libVersion", 1.4),
					contentRating = TachiyomiContentRating.valueOf(obj.optString("contentRating").ifBlank { if (obj.optBoolean("isNsfw")) "NSFW" else "UNSPECIFIED" }),
					sourceCount = obj.optInt("sourceCount"),
					sources =
						buildList {
							val sourceArray = obj.optJSONArray("sources") ?: return@buildList
							for (index in 0 until sourceArray.length()) {
								val source = sourceArray.optJSONObject(index) ?: continue
								add(TachiyomiCatalogSource(source.optLong("id"), source.optString("name"), source.optString("language", "all"), source.optString("homeUrl").takeIf { it.isNotBlank() }, TachiyomiContentRating.fromCatalog(source.optString("contentRating").takeIf { it.isNotBlank() }, obj.optString("extensionLib").toDoubleOrNull())))
							}
						},
				)
			}.getOrNull()
	}
}

data class DirectTachiyomiFailure(
	val packageName: String,
	val message: String,
)

private const val CATALOG_KEY_REPOSITORIES = "repositories"
private const val CATALOG_KEY_IGNORED_PACKAGES = "ignored_packages"
private const val CATALOG_KEY_REPOSITORY_NAMES = "repository_names"

@Singleton
class TachiyomiExtensionCatalogProvider
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		@BaseHttpClient private val httpClient: OkHttpClient,
	) {
		private val preferences by lazy { context.getSharedPreferences("tachiyomi_catalogs", Context.MODE_PRIVATE) }
		private val catalogClient by lazy {
			httpClient
				.newBuilder()
				.apply {
					interceptors().clear()
					networkInterceptors().clear()
					cache(null)
					retryOnConnectionFailure(true)
					followRedirects(true)
					followSslRedirects(true)
				}.build()
		}
		private val directCatalogClient by lazy {
			OkHttpClient
				.Builder()
				.connectTimeout(20, TimeUnit.SECONDS)
				.readTimeout(90, TimeUnit.SECONDS)
				.writeTimeout(20, TimeUnit.SECONDS)
				.retryOnConnectionFailure(true)
				.followRedirects(true)
				.followSslRedirects(true)
				.build()
		}

		@Volatile
		var lastLoadError: String? = null
			private set

		fun saveRepository(input: String) {
			val normalized = normalizeUrl(input) ?: return
			val current = preferences.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_REPOSITORIES, current + normalized) }
		}

		fun repositoryName(input: String): String? {
			val normalized = normalizeUrl(input) ?: return null
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrNull() ?: return null
			return names.optString(normalized).takeIf { it.isNotBlank() }
		}

		fun setRepositoryName(
			input: String,
			name: String?,
		) {
			val normalized = normalizeUrl(input) ?: return
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrElse { JSONObject() }
			if (name.isNullOrBlank()) names.remove(normalized) else names.put(normalized, name.trim())
			preferences.edit { putString(CATALOG_KEY_REPOSITORY_NAMES, names.toString()) }
		}

		fun removeRepository(input: String) {
			val normalized = normalizeUrl(input) ?: return
			val current = preferences.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet()).orEmpty()
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrElse { JSONObject() }
			names.remove(normalized)
			preferences.edit {
				putStringSet(CATALOG_KEY_REPOSITORIES, current - normalized)
				putString(CATALOG_KEY_REPOSITORY_NAMES, names.toString())
			}
		}

		fun ignorePackage(packageName: String) {
			val current = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_IGNORED_PACKAGES, current + packageName) }
		}

		fun restorePackage(packageName: String) {
			val current = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_IGNORED_PACKAGES, current - packageName) }
		}

		suspend fun loadSaved(): List<TachiyomiExtensionArtifact> =
			withContext(Dispatchers.IO) {
				val ignored = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
				preferences
					.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet())
					.orEmpty()
					.flatMap { url -> load(url) }
					.filterNot { it.packageName in ignored }
			}

		suspend fun load(input: String): List<TachiyomiExtensionArtifact> =
			withContext(Dispatchers.IO) {
				val normalized = normalizeUrl(input)
				val urls = normalized?.let(::candidateUrls).orEmpty()
				if (urls.isEmpty()) {
					lastLoadError = "Invalid repository URL"
					return@withContext emptyList()
				}
				val errors = ArrayList<String>(urls.size * 2)
				val clients = listOf("configured network" to catalogClient, "direct network" to directCatalogClient)
				for (url in urls) {
					val request =
						Request
							.Builder()
							.url(url)
							.header("Accept", "application/json")
							.header("User-Agent", "Usagi-TachiyomiCatalog/1.0")
							.get()
							.build()
					for ((clientName, client) in clients) {
						val result =
							runCatching {
								client.newCall(request).execute().use { response ->
									if (!response.isSuccessful) {
										errors += "$clientName: $url → HTTP ${response.code} ${response.message}"
										return@use emptyList<TachiyomiExtensionArtifact>()
									}
									val body = decodeCatalogBody(url, response.body?.string().orEmpty())
									val parsed = parse(normalized ?: url, body)
									if (parsed.isEmpty()) errors += "$clientName: $url → Catalog has no supported extensions"
									parsed
								}
							}.getOrElse { error ->
								errors += "$clientName: $url → ${error.message ?: error.javaClass.simpleName}"
								emptyList()
							}
						if (result.isNotEmpty()) {
							lastLoadError = null
							return@withContext result
						}
					}
				}

				lastLoadError = errors.takeLast(3).joinToString("\n").ifBlank { "Catalog could not be parsed" }

				emptyList()
			}

		fun normalizeUrl(input: String): String? {
			val raw = input.trim().removeSuffix("/")
			if (raw.isBlank()) return null
			val github = GITHUB_REPOSITORY_REGEX.matchEntire(raw)
			if (github != null) return "https://raw.githubusercontent.com/${github.groupValues[1]}/${github.groupValues[2]}/main/index.json"
			if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
			val repo = raw.removePrefix("github.com/").removePrefix("www.github.com/")
			val parts = repo.split('/').filter { it.isNotBlank() }
			if (parts.size == 2) return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/index.json"
			return null
		}

		private fun candidateUrls(normalized: String): List<String> {
			val rawGithub = RAW_GITHUB_INDEX_REGEX.matchEntire(normalized)
			if (rawGithub == null) return listOf(normalized)
			val owner = rawGithub.groupValues[1]
			val repository = rawGithub.groupValues[2]
			val refs = listOf("main", "master", "repo")
			val rawUrls = refs.map { ref -> "https://raw.githubusercontent.com/$owner/$repository/$ref/index.json" }
			val cdnUrls = refs.map { ref -> "https://cdn.jsdelivr.net/gh/$owner/$repository@$ref/index.json" }
			val apiUrls = refs.map { ref -> "https://api.github.com/repos/$owner/$repository/contents/index.json?ref=$ref" }
			return (rawUrls + cdnUrls + apiUrls + normalized).distinct()
		}

		private fun decodeCatalogBody(
			url: String,
			body: String,
		): String {
			if (!url.startsWith("https://api.github.com/repos/")) return body
			val response = JSONObject(body)
			if (!response.optString("encoding").equals("base64", true)) return body
			val content = response.optString("content").filterNot(Char::isWhitespace)
			if (content.isBlank()) error("GitHub Contents API returned an empty catalog")
			return Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
		}

		private companion object {
			val GITHUB_REPOSITORY_REGEX = Regex("(?i)^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:/.*)?$")
			val RAW_GITHUB_INDEX_REGEX = Regex("(?i)^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/index\\.json$")
		}

		private fun parse(
			repositoryUrl: String,
			body: String,
		): List<TachiyomiExtensionArtifact> =
			runCatching {
				val root = JSONObject(body.removePrefix("\uFEFF"))
				val extensionList = root.optJSONObject("extensionList")
				val extensions =
					extensionList?.optJSONArray("extensions")
						?: root.optJSONArray("extensions")
						?: JSONArray()

				buildList {
					for (index in 0 until extensions.length()) {
						val obj = extensions.optJSONObject(index) ?: continue
						val packageName = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
						val resources = obj.optJSONObject("resources")
						val extensionLib = obj.optString("extensionLib").toDoubleOrNull()
						val catalogRating =
							TachiyomiContentRating.fromCatalog(
								obj.opt("contentRating")?.toString()?.takeIf { it.isNotBlank() }
									?: obj.opt("contentWarning")?.toString()?.takeIf { it.isNotBlank() },
								extensionLib,
							)
						val sourceObjects = obj.optJSONArray("sources")
						val catalogSources =
							buildList {
								if (sourceObjects != null) {
									for (sourceIndex in 0 until sourceObjects.length()) {
										val source = sourceObjects.optJSONObject(sourceIndex) ?: continue
										val id = source.optString("id").toLongOrNull() ?: continue
										val language = source.optString("language", "all").takeIf { it.isNotBlank() } ?: "all"
										val sourceRating =
											source.opt("contentRating")?.toString()?.takeIf { it.isNotBlank() }
												?: source.opt("contentWarning")?.toString()?.takeIf { it.isNotBlank() }
										add(TachiyomiCatalogSource(id, source.optString("name", packageName), language, source.optString("homeUrl").takeIf { it.isNotBlank() }, TachiyomiContentRating.fromCatalog(sourceRating, extensionLib)))
									}
								}
							}
						val sourceCount = catalogSources.size
						add(
							TachiyomiExtensionArtifact(
								repositoryUrl = repositoryUrl,
								name = obj.optString("name", packageName),
								packageName = packageName,
								jarUrl = resources?.optString("jarUrl")?.takeIf { !it.isNullOrBlank() },
								apkUrl = resources?.optString("apkUrl")?.takeIf { !it.isNullOrBlank() },
								iconUrl = resources?.optString("iconUrl")?.takeIf { !it.isNullOrBlank() },
								extensionLib = extensionLib,
								versionCode = obj.optString("versionCode").toLongOrNull(),
								versionName = obj.optString("versionName").takeIf { it.isNotBlank() },
								contentRating = if (obj.optBoolean("isNsfw")) TachiyomiContentRating.NSFW else catalogRating,
								sourceCount = sourceCount,
								sources = catalogSources,
							),
						)
					}
				}
			}.getOrElse { error ->
				throw IllegalArgumentException("Catalog JSON parse failed: ${error.message ?: error.javaClass.simpleName}", error)
			}
	}

private class DirectDexClassLoader(
	dexPath: String,
	optimizedDirectory: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
	private val systemClassLoader = ClassLoader.getSystemClassLoader()

	override fun loadClass(
		name: String,
		resolve: Boolean,
	): Class<*> {
		var loaded = findLoadedClass(name)
		if (loaded == null) {
			loaded = runCatching { systemClassLoader?.loadClass(name) }.getOrNull()
		}
		if (loaded == null) {
			loaded = runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
		}
		if (resolve) resolveClass(loaded)
		return loaded
	}
}

private class DirectLanguageResolver(
	private val context: Context,
) {
	private val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	fun getVariants(
		source: TachiyomiMangaSource,
		sources: List<TachiyomiMangaSource>,
	) = sources.filter { it.pkgName == source.pkgName && it.displayName == source.displayName }

	fun getActiveLanguage(
		source: TachiyomiMangaSource,
		sources: List<TachiyomiMangaSource>,
	): String? = selectLanguage(getVariants(source, sources))

	fun selectActive(sources: List<TachiyomiMangaSource>): List<TachiyomiMangaSource> =
		sources.groupBy { it.pkgName to it.displayName }.values.map { variants ->
			val language = selectLanguage(variants)
			variants.firstOrNull { it.locale.equals(language, true) } ?: variants.first()
		}

	fun resolve(
		source: TachiyomiMangaSource,
		sources: List<TachiyomiMangaSource>,
	): TachiyomiMangaSource = getVariants(source, sources).firstOrNull { it.locale.equals(selectLanguage(getVariants(source, sources)), true) } ?: source

	fun setActiveLanguage(
		source: TachiyomiMangaSource,
		language: String,
	) {
		val suffix = "\n${source.pkgName}\n${source.displayName}"
		val current = preferences.getStringSet("tachiyomi_source_languages", emptySet()).orEmpty()
		preferences.edit { putStringSet("tachiyomi_source_languages", current.filterNot { it.endsWith(suffix) }.toSet() + (language + suffix)) }
	}

	private fun selectLanguage(variants: List<TachiyomiMangaSource>): String? {
		if (variants.isEmpty()) return null
		val languages = variants.map { it.locale }
		val suffix = "\n${variants.first().pkgName}\n${variants.first().displayName}"
		val stored =
			preferences
				.getStringSet("tachiyomi_source_languages", emptySet())
				.orEmpty()
				.firstOrNull { it.endsWith(suffix) }
				?.substringBefore('\n')
		return match(languages, stored) ?: match(languages, ConfigurationCompat.getLocales(context.resources.configuration).get(0)?.language) ?: match(languages, "en") ?: languages.first()
	}

	private fun match(
		values: List<String>,
		target: String?,
	): String? {
		if (target.isNullOrBlank()) return null
		val base = target.substringBefore('-').substringBefore('_')
		return values.firstOrNull { it.equals(target, true) } ?: values.firstOrNull { it.substringBefore('-').substringBefore('_').equals(base, true) }
	}
}
