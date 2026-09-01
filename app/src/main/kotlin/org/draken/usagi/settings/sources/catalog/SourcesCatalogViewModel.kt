package org.draken.usagi.settings.sources.catalog

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.draken.tsukimix.core.parser.external.ExtRuntime
import org.draken.tsukimix.core.parser.external.ExtensionProvider
import org.draken.tsukimix.core.parser.external.NativeExtManager
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.tsukimix.core.parser.external.model.ExtInstalled
import org.draken.tsukimix.core.parser.external.model.ExtSource
import org.draken.tsukimix.core.util.canonicalLanguageCode
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.model.DirectExternalPluginMetadata
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.usagi.core.model.unwrap
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.mapSortedByCount
import org.draken.usagi.core.util.ext.toLocale
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.explore.data.SourcesSortOrder
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import tsuki.model.ContentType
import tsuki.model.MangaSource
import tsuki.util.toTitleCase
import java.io.File
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject
import org.draken.tsukimix.core.parser.external.ExtensionManager as InstalledExtManager
import org.draken.tsukimix.core.parser.external.model.Manga as ExtMangaSource

@HiltViewModel
class SourcesCatalogViewModel
	@Inject
	constructor(
		savedStateHandle: SavedStateHandle,
		@ApplicationContext private val context: Context,
		private val repository: MangaSourcesRepository,
		db: MangaDatabase,
		private val settings: AppSettings,
		private val directManager: NativeExtManager,
		private val installedExtManager: InstalledExtManager,
		private val runtime: ExtRuntime,
		private val catalogProvider: ExtensionProvider,
	) : BaseViewModel() {
		val scopedRepositoryUrl: String? = savedStateHandle.get<String>(EXTRA_REPOSITORY_URL)?.takeIf { it.isNotBlank() }
		val isScopedMode get() = scopedRepositoryUrl != null
		val onActionDone = MutableEventFlow<ReversibleAction>()
		val onActionError = MutableEventFlow<Int>()
		val onOpenSource = MutableEventFlow<MangaSource>()

		private val extCatalog = MutableStateFlow<List<ExtArtifact>>(emptyList())
		private val isInitialLoading = MutableStateFlow(true)
		private val searchQuery = MutableStateFlow<String?>(null)
		private val installingPackages = MutableStateFlow<Set<String>>(emptySet())

		val locales = MutableStateFlow<Set<String?>>(setOf(null))
		val appliedFilter =
			MutableStateFlow(
				SourcesCatalogFilter(types = emptySet(), locale = null, isNewOnly = false, plugin = null),
			)
		val hasNewSources =
			repository
				.observeHasNewSources()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

		val plugins: List<Pair<String, String>>
			get() =
				buildMap {
					repository.allMangaSources.forEach { s ->
						val p = (s as? PluginMangaSource) ?: (s as? MangaSourceInfo)?.mangaSource as? PluginMangaSource
						p?.let { put(it.jarName, it.jarName.removeSuffix(".jar")) }
					}
					directManager.installed.value.forEach { r ->
						val repoName = DirectExternalPluginMetadata.get(r.packageName)
						if (repoName != null) {
							put(repoName, repoName)
						} else {
							val name =
								r.name
									.removePrefix("Extension: ")
									.removePrefix("Extension - ")
									.trim()
							put(r.packageName, name)
						}
					}
				}.toList().sortedBy { it.second.lowercase(Locale.ROOT) }

		val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

		private val extState =
			combine(
				directManager.installed,
				runtime.sources,
				installedExtManager.sources,
				isInitialLoading,
				installingPackages,
			) { inst, loaded, pre, load, installing ->
				CatalogExtState(
					inst,
					loaded.map { it.sourceId }.toSet(),
					pre.filter { it.isPreInstalled },
					load,
					installing,
				)
			}

		val content: StateFlow<List<ListModel>> =
			combine(
				searchQuery,
				appliedFilter,
				db.invalidationTracker.createFlow(TABLE_SOURCES),
				extCatalog,
				extState,
			) { q, filter, _, arts, (inst, loaded, pre, load, installing) ->
				if (load) {
					listOf(LoadingState)
				} else {
					buildSourcesList(filter, q, arts, inst, loaded, pre, installing)
				}
			}.stateIn(
				viewModelScope + Dispatchers.Default,
				SharingStarted.Eagerly,
				listOf(LoadingState),
			)

		init {
			repository.clearNewSourcesBadge()
			launchJob(Dispatchers.Default) {
				val cached = catalogProvider.loadSavedCached()
				extCatalog.value = cached
				contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
				isInitialLoading.value = false
				launch { runCatching { runtime.ensureReady() } }
				launch {
					val ref = catalogProvider.loadSaved()
					if (ref != cached) extCatalog.value = ref
				}
			}
		}

		fun performSearch(query: String?) {
			searchQuery.value = query
		}

		fun setLocale(value: String?) {
			appliedFilter.value = appliedFilter.value.copy(locale = value)
		}

		fun refreshExtensionRuntime() {
			launchJob(Dispatchers.Default) { runCatching { runtime.ensureReady(forceRefresh = true) } }
		}

		fun createDownloadRequest(
			item: SourceCatalogItem.Extension,
			destinationDir: File?,
		): DownloadManager.Request? {
			val apkUrl =
				item.artifact.apkUrl
					?.trim()
					?.takeIf { it.isNotEmpty() } ?: return null
			val uri = apkUrl.toUri()
			val name =
				uri.lastPathSegment?.takeIf { it.endsWith(".apk", true) }
					?: "${item.artifact.packageName}-${item.artifact.versionName
						?: item.artifact.versionCode ?: "latest"}.apk"
			val req =
				DownloadManager
					.Request(uri)
					.setTitle(item.displayName)
					.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
					.setMimeType(APK_MIME_TYPE)
			if (destinationDir != null) req.setDestinationUri(Uri.fromFile(File(destinationDir, name)))
			return req
		}

		suspend fun openExtensionSource(item: SourceCatalogItem.Extension): MangaSource? {
			var source = runCatching { getImportedExtensionSource(item) }.getOrNull()
			if (source == null && !item.isInstalled && !item.isLoaded && !item.isPreInstalledApk) {
				if (performInstall(item)) source = runCatching { getImportedExtensionSource(item) }.getOrNull()
			}
			if (source != null) enableExtensionSource(source)
			return source
		}

		fun openSource(item: SourceCatalogItem.Extension) {
			launchJob {
				val source = openExtensionSource(item)
				if (source != null) {
					onOpenSource.call(source)
				} else {
					onActionError.call(R.string.unsupported_source)
				}
			}
		}

		fun localeDisplayName(value: String?): String {
			val code = value?.trim().orEmpty()
			if (code.isEmpty()) return context.getString(R.string.all_languages)
			if (code.equals("all", true)) return context.getString(R.string.various_languages)
			val loc = code.replace('_', '-').toLocale()
			val name = loc.getDisplayName(loc).trim()
			if (!name.isLocaleCode(loc)) return name.toTitleCase(loc)
			val en = loc.getDisplayName(Locale.ENGLISH).trim()
			return en.takeUnless { it.isLocaleCode(loc) }?.toTitleCase(Locale.ENGLISH)
				?: code.ifEmpty { context.getString(R.string.unknown) }
		}

		private fun String.isLocaleCode(loc: Locale): Boolean {
			val norm = trim().lowercase(Locale.ROOT)
			return norm.isEmpty() ||
				norm == loc.language.lowercase(Locale.ROOT) ||
				norm == loc.toLanguageTag().lowercase(Locale.ROOT)
		}

		private fun scopedArtifacts(artifacts: List<ExtArtifact>): List<ExtArtifact> {
			val scoped = scopedRepositoryUrl ?: return emptyList()
			val norm = catalogProvider.normalizeUrl(scoped) ?: scoped
			val filtered = artifacts.filter { (catalogProvider.normalizeUrl(it.repositoryUrl) ?: it.repositoryUrl) == norm }
			if (filtered.isNotEmpty()) return filtered
			val installed =
				directManager.installed.value.filter {
					it.repositoryUrl == scoped || it.packageName == scoped.removePrefix("local://")
				}
			return installed.map { it.toArtifact() }
		}

		fun install(item: SourceCatalogItem.Extension) {
			launchJob {
				if (!performInstall(item)) {
					onActionError.call(R.string.load_failed)
				}
			}
		}

		private suspend fun performInstall(item: SourceCatalogItem.Extension): Boolean =
			try {
				installingPackages.update { it + item.artifact.packageName }
				if (item.isInstalled && !item.hasUpdate && !item.isLoaded) {
					val s = getImportedExtensionSource(item) ?: return false
					repository.setSourcesEnabled(setOf(s), true)
					runtime.ensureReady(forceRefresh = true)
					true
				} else {
					val ok = directManager.install(item.artifact)
					if (ok) {
						runtime.ensureReady(forceRefresh = true)
						getImportedExtensionSource(item)?.let { repository.setSourcesEnabled(setOf(it), true) }
					}
					ok
				}
			} catch (_: Throwable) {
				false
			} finally {
				installingPackages.update { it - item.artifact.packageName }
			}

		fun uninstall(item: SourceCatalogItem.Extension) {
			launchJob {
				val ok =
					runCatching {
						val removed = directManager.remove(item.artifact.packageName)
						if (removed) runtime.ensureReady(forceRefresh = true)
						removed
					}.getOrDefault(false)
				if (!ok) onActionError.call(R.string.load_failed)
			}
		}

		suspend fun getImportedExtensionSource(item: SourceCatalogItem.Extension): MangaSource? {
			runtime.ensureReady()
			return runtime.getSourceById(item.source.id)
				?: runtime.getSourceByName(item.source.name)
				?: directManager.sources.value.firstOrNull { it.matches(item) }
				?: installedExtManager.sources.value.firstOrNull { it.matches(item) }
				?: repository.allMangaSources.firstOrNull { s ->
					when (val u = s.unwrap()) {
						is ExtMangaSource -> {
							u.matches(item) ||
								(
									u.pkgName == item.artifact.packageName &&
										u.displayName.equals(item.source.name, true)
								)
						}

						is ExternalMangaSource -> {
							u.packageName == item.artifact.packageName
						}

						else -> {
							false
						}
					}
				}
		}

		suspend fun enableExtensionSource(source: MangaSource) {
			repository.setSourcesEnabled(setOf(source), true)
			runtime.ensureReady(forceRefresh = true)
		}

		private fun ExtMangaSource.matches(item: SourceCatalogItem.Extension) = matchesCatalog(item.artifact, item.source)

		private fun ExtMangaSource.matchesCatalog(
			art: ExtArtifact,
			src: ExtSource,
		) = pkgName == art.packageName &&
			displayName.equals(src.name, true) &&
			canonicalLanguageCode(locale) == canonicalLanguageCode(src.language)

		private data class CatalogExtState(
			val installed: List<ExtInstalled>,
			val loadedSourceIds: Set<Long>,
			val preInstalledSources: List<ExtMangaSource>,
			val loading: Boolean,
			val installingPackages: Set<String>,
		)

		fun addSource(source: MangaSource) {
			launchJob(Dispatchers.Default) {
				val all = repository.allMangaSources.filter { it.title.equals(source.title, true) }.ifEmpty { listOf(source) }
				val rollback = repository.setSourcesEnabled(all, true)
				onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
			}
		}

		fun setContentType(
			value: ContentType,
			isAdd: Boolean,
		) {
			val f = appliedFilter.value
			val types =
				EnumSet.noneOf(ContentType::class.java).apply {
					addAll(f.types)
					if (isAdd) add(value) else remove(value)
				}
			appliedFilter.value = f.copy(types = types)
		}

		fun setNewOnly(value: Boolean) {
			appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
		}

		fun setPlugin(value: String?) {
			appliedFilter.value = appliedFilter.value.copy(plugin = value)
		}

		private suspend fun buildSourcesList(
			filter: SourcesCatalogFilter,
			query: String?,
			artifacts: List<ExtArtifact>,
			installed: List<ExtInstalled>,
			loadedIds: Set<Long>,
			pre: List<ExtMangaSource>,
			installing: Set<String>,
		): List<SourceCatalogItem> {
			if (!isScopedMode) {
				val sources =
					repository.queryParserSources(
						isDisabledOnly = true,
						isNewOnly = filter.isNewOnly,
						excludeBroken = false,
						types = filter.types,
						query = null,
						locale = null,
						plugin = filter.plugin,
						sortOrder = SourcesSortOrder.ALPHABETIC,
					)
				val grouped =
					sources.groupBy { s: MangaSource ->
						val ps = (s as? PluginMangaSource) ?: (s as? MangaSourceInfo)?.mangaSource as? PluginMangaSource
						ps?.jarName.orEmpty() to s.title
					}
				locales.value =
					buildSet {
						add(null)
						grouped.values.forEach { v ->
							if (v.size > 1 || v.any { it.locale.isBlank() || it.locale.equals("all", true) }) {
								add("all")
							} else {
								v.forEach { add(it.locale.ifBlank { "all" }) }
							}
						}
					}
				val res = ArrayList<SourceCatalogItem.Source>(grouped.size)
				for ((_, variants) in grouped) {
					val multi = variants.size > 1 || variants.any { it.locale.equals("all", true) }
					if (filter.locale != null) {
						if (filter.locale.equals("all", true)) {
							if (!multi) continue
						} else {
							val hasLocale =
								variants.any {
									it.locale.ifBlank { "all" }.equals(filter.locale, true)
								}
							if (multi || !hasLocale) continue
						}
					}
					val defaultLang = Locale.getDefault().language
					val pref =
						variants.firstOrNull { it.locale.equals(defaultLang, true) }
							?: variants.firstOrNull { it.locale.equals("en", true) }
							?: variants.first()
					if (!query.isNullOrBlank() &&
						!pref.title.contains(query, true) &&
						!pref.name.contains(query, true)
					) {
						continue
					}
					res.add(SourceCatalogItem.Source(pref, isMultiLanguage = multi))
				}
				return res.ifEmpty {
					val title =
						if (query == null) {
							R.string.no_manga_sources
						} else {
							R.string.nothing_found
						}
					val sub =
						if (query == null) {
							R.string.no_manga_sources_catalog_text
						} else {
							R.string.no_manga_sources_found
						}
					listOf(SourceCatalogItem.Hint(R.drawable.ic_empty_feed, title, sub))
				}
			}

			val scoped = scopedArtifacts(artifacts)
			locales.value =
				buildSet {
					add(null)
					scoped.forEach { a ->
						val isMulti =
							a.sources.size > 1 ||
								a.sources.any { it.language.isBlank() || it.language.equals("all", true) }
						if (isMulti) {
							add("all")
						} else {
							a.sources.forEach { add(it.language.ifBlank { "all" }) }
						}
					}
				}
			val instMap = installed.associateBy { it.packageName }
			val extSources = ArrayList<SourceCatalogItem.Extension>()
			for (art in scoped) {
				val multi =
					art.sources.size > 1 ||
						art.sources.any { it.language.equals("all", true) }
				if (filter.locale != null) {
					if (filter.locale.equals("all", true)) {
						if (!multi) continue
					} else {
						val hasLang =
							art.sources.any {
								it.language.ifBlank { "all" }.equals(filter.locale, true)
							}
						if (multi || !hasLang) continue
					}
				}
				val defLang = Locale.getDefault().language
				val c =
					art.sources.firstOrNull {
						canonicalLanguageCode(it.language) == canonicalLanguageCode(defLang)
					} ?: art.sources.firstOrNull { it.language.equals("en", true) }
						?: art.sources.firstOrNull() ?: continue
				if (settings.isNsfwContentDisabled && c.contentType == ContentType.HENTAI) continue
				if (filter.types.isNotEmpty() && c.contentType !in filter.types) continue
				if (!query.isNullOrBlank() &&
					!c.name.contains(query, true) &&
					!art.name.contains(query, true) &&
					!art.packageName.contains(query, true)
				) {
					continue
				}
				val custom =
					catalogProvider.repositoryName(art.repositoryUrl)
						?: DirectExternalPluginMetadata.get(art.packageName)
				extSources.add(
					SourceCatalogItem.Extension(
						c,
						art,
						instMap[art.packageName],
						c.id in loadedIds,
						pre.any { it.matchesCatalog(art, c) },
						multi,
						art.packageName in installing,
						custom,
					),
				)
			}
			return extSources.ifEmpty {
				val title =
					if (query == null) {
						R.string.no_manga_sources
					} else {
						R.string.nothing_found
					}
				val sub =
					if (query == null) {
						R.string.no_manga_sources_catalog_text
					} else {
						R.string.no_manga_sources_found
					}
				listOf(SourceCatalogItem.Hint(R.drawable.ic_empty_feed, title, sub))
			}
		}

		@WorkerThread
		private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
			val all =
				if (!isScopedMode) {
					repository.allMangaSources.mapSortedByCount { it.contentType }
				} else {
					scopedArtifacts(extCatalog.value)
						.flatMap { it.sources }
						.mapSortedByCount { it.contentType }
				}
			return if (isNsfwDisabled) all.filterNot { it == ContentType.HENTAI } else all
		}

		companion object {
			const val APK_MIME_TYPE = "application/vnd.android.package-archive"
			const val EXTRA_REPOSITORY_URL = "repository_url"
			const val EXTRA_REPOSITORY_NAME = "repository_name"
		}
	}
