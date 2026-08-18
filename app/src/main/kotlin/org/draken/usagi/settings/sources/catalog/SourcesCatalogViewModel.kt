package org.draken.usagi.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.draken.tsukimix.core.parser.tachiyomi.ExtensionProvider
import org.draken.tsukimix.core.parser.tachiyomi.NativeExtManager
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiRuntime
import org.draken.tsukimix.core.parser.tachiyomi.model.DirectTachiyomiInstalled
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiCatalogSource
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionArtifact
import org.draken.tsukimix.core.util.canonicalLanguageCode
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.model.DirectTachiyomiPluginMetadata
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.model.unwrap
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.mapSortedByCount
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.explore.data.SourcesSortOrder
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import tsuki.model.ContentType
import tsuki.model.MangaSource
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject
import org.draken.tsukimix.core.parser.tachiyomi.ExtensionManager as InstalledTachiyomiExtensionManager
import org.draken.tsukimix.core.parser.tachiyomi.model.Manga as TachiyomiMangaSource

@HiltViewModel
class SourcesCatalogViewModel
	@Inject
	constructor(
		savedStateHandle: SavedStateHandle,
		private val repository: MangaSourcesRepository,
		db: MangaDatabase,
		private val settings: AppSettings,
		private val directManager: NativeExtManager,
		private val installedTachiyomiManager: InstalledTachiyomiExtensionManager,
		private val tachiyomiRuntime: TachiyomiRuntime,
		private val catalogProvider: ExtensionProvider,
	) : BaseViewModel() {
		val scopedRepositoryUrl: String? = savedStateHandle.get<String>(EXTRA_REPOSITORY_URL)?.takeIf { it.isNotBlank() }
		val isScopedMode: Boolean get() = scopedRepositoryUrl != null

		val onActionDone = MutableEventFlow<ReversibleAction>()

		private val tachiyomiCatalog = MutableStateFlow<List<TachiyomiExtensionArtifact>>(emptyList())
		private val isInitialLoading = MutableStateFlow(true)
		private val searchQuery = MutableStateFlow<String?>(null)
		private val installingPackages = MutableStateFlow<Set<String>>(emptySet())

		val locales = MutableStateFlow<Set<String?>>(setOf(null))

		val appliedFilter =
			MutableStateFlow(
				SourcesCatalogFilter(
					types = emptySet(),
					locale = null,
					isNewOnly = false,
					plugin = null,
				),
			)

		val hasNewSources =
			repository
				.observeHasNewSources()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

		val plugins: List<SourceCatalogPlugin>
			get() =
				buildMap<String, SourceCatalogPlugin> {
					repository.allMangaSources.forEach { source ->
						val plugin =
							(source as? org.draken.usagi.core.model.PluginMangaSource)
								?: (source as? MangaSourceInfo)?.mangaSource as? org.draken.usagi.core.model.PluginMangaSource
						plugin?.let { put(it.jarName, SourceCatalogPlugin(it.jarName, it.jarName.removeSuffix(".jar"))) }
					}
					directManager.installed.value.forEach { record ->
						val pluginName =
							DirectTachiyomiPluginMetadata.get(record.packageName)
								?: record.name
									.removePrefix("Tachiyomi: ")
									.removePrefix("Tachiyomi - ")
									.trim()
						put(record.packageName, SourceCatalogPlugin(record.packageName, pluginName))
					}
				}.values.sortedBy { it.label.lowercase(Locale.ROOT) }

		val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

		private val tachiyomiState =
			combine(directManager.installed, tachiyomiRuntime.sources, installedTachiyomiManager.sources, isInitialLoading, installingPackages) { installed, loaded, preInstalled, loading, installing ->
				CatalogTachiyomiState(installed, loaded.map { it.sourceId }.toSet(), preInstalled.filter { it.isPreInstalled }, loading, installing)
			}

		val content: StateFlow<List<ListModel>> =
			combine(
				searchQuery,
				appliedFilter,
				db.invalidationTracker.createFlow(TABLE_SOURCES),
				tachiyomiCatalog,
				tachiyomiState,
			) { query, filter, _, artifacts, (installed, loaded, preInstalled, loading, installing) ->
				if (loading) listOf(LoadingState) else buildSourcesList(filter, query, artifacts, installed, loaded, preInstalled, installing)
			}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		init {
			repository.clearNewSourcesBadge()
			launchJob(Dispatchers.Default) {
				val cachedCatalog = catalogProvider.loadSavedCached()
				tachiyomiCatalog.value = cachedCatalog
				contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
				isInitialLoading.value = false

				launch {
					runCatching { tachiyomiRuntime.ensureReady() }
				}
				launch {
					val refreshedCatalog = catalogProvider.loadSaved()
					if (refreshedCatalog != cachedCatalog) {
						tachiyomiCatalog.value = refreshedCatalog
					}
				}
			}
		}

		fun performSearch(query: String?) {
			searchQuery.value = query
		}

		fun setLocale(value: String?) {
			appliedFilter.value = appliedFilter.value.copy(locale = value)
		}

		fun refreshTachiyomiRuntime() {
			launchJob(Dispatchers.Default) {
				runCatching { tachiyomiRuntime.ensureReady(forceRefresh = true) }
			}
		}

		private fun scopedArtifacts(artifacts: List<TachiyomiExtensionArtifact>): List<TachiyomiExtensionArtifact> {
			val scoped = scopedRepositoryUrl ?: return emptyList()
			val normScoped = catalogProvider.normalizeUrl(scoped) ?: scoped
			val filtered = artifacts.filter { (catalogProvider.normalizeUrl(it.repositoryUrl) ?: it.repositoryUrl) == normScoped }
			if (filtered.isNotEmpty()) return filtered
			val installed = directManager.installed.value.filter { it.repositoryUrl == scoped || it.packageName == scoped.removePrefix("local://") }
			return installed.map { it.toArtifact() }
		}

		suspend fun installTachiyomi(item: SourceCatalogItem.Tachiyomi): Boolean {
			return try {
				installingPackages.update { it + item.artifact.packageName }
				if (item.isInstalled && !item.hasUpdate && !item.isLoaded) {
					val source = getImportedTachiyomiSource(item) ?: return false
					repository.setSourcesEnabled(setOf(source), true)
					tachiyomiRuntime.ensureReady(forceRefresh = true)
					true
				} else {
					val installed = directManager.install(item.artifact)
					if (installed) {
						tachiyomiRuntime.ensureReady(forceRefresh = true)
						val source = getImportedTachiyomiSource(item)
						if (source != null) {
							repository.setSourcesEnabled(setOf(source), true)
						}
					}
					installed
				}
			} catch (_: Throwable) {
				false
			} finally {
				installingPackages.update { it - item.artifact.packageName }
			}
		}

		suspend fun uninstallTachiyomi(item: SourceCatalogItem.Tachiyomi): Boolean =
			runCatching {
				val removed = directManager.remove(item.artifact.packageName)
				if (removed) {
					tachiyomiRuntime.ensureReady(forceRefresh = true)
				}
				removed
			}.getOrDefault(false)

		suspend fun unloadTachiyomi(item: SourceCatalogItem.Tachiyomi): Boolean {
			val source = getImportedTachiyomiSource(item) ?: return false
			repository.setSourcesEnabled(setOf(source), false)
			tachiyomiRuntime.ensureReady(forceRefresh = true)
			return true
		}

		suspend fun getImportedTachiyomiSource(item: SourceCatalogItem.Tachiyomi): MangaSource? {
			tachiyomiRuntime.ensureReady()
			return tachiyomiRuntime.getSourceById(item.source.id)
				?: tachiyomiRuntime.getSourceByName(item.source.name)
				?: directManager.sources.value.firstOrNull { it.matchesCatalogItem(item) }
				?: installedTachiyomiManager.sources.value.firstOrNull { it.matchesCatalogItem(item) }
				?: repository.allMangaSources.firstOrNull { source ->
					when (val unwrapped = source.unwrap()) {
						is TachiyomiMangaSource -> unwrapped.matchesCatalogItem(item) || (unwrapped.pkgName == item.artifact.packageName && unwrapped.displayName.equals(item.source.name, ignoreCase = true))
						is ExternalMangaSource -> unwrapped.packageName == item.artifact.packageName
						else -> false
					}
				}
		}

		suspend fun enableTachiyomiSource(source: MangaSource) {
			repository.setSourcesEnabled(setOf(source), true)
			tachiyomiRuntime.ensureReady(forceRefresh = true)
		}

		private fun TachiyomiMangaSource.matchesCatalogItem(item: SourceCatalogItem.Tachiyomi): Boolean = matchesCatalogSource(item.artifact, item.source)

		private fun TachiyomiMangaSource.matchesCatalogSource(
			artifact: TachiyomiExtensionArtifact,
			source: TachiyomiCatalogSource,
		): Boolean =
			pkgName == artifact.packageName &&
				displayName.equals(source.name, ignoreCase = true) &&
				canonicalLanguageCode(locale) == canonicalLanguageCode(source.language)

		private data class CatalogTachiyomiState(
			val installed: List<DirectTachiyomiInstalled>,
			val loadedSourceIds: Set<Long>,
			val preInstalledSources: List<TachiyomiMangaSource>,
			val loading: Boolean,
			val installingPackages: Set<String>,
		)

		fun addSource(source: MangaSource) {
			launchJob(Dispatchers.Default) {
				val allVariants =
					repository.allMangaSources
						.filter { it.title.equals(source.title, ignoreCase = true) }
						.ifEmpty { listOf(source) }
				val rollback = repository.setSourcesEnabled(allVariants, true)
				onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
			}
		}

		fun setContentType(
			value: ContentType,
			isAdd: Boolean,
		) {
			val filter = appliedFilter.value
			val types = EnumSet.noneOf(ContentType::class.java)
			types.addAll(filter.types)
			if (isAdd) types.add(value) else types.remove(value)
			appliedFilter.value = filter.copy(types = types)
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
			artifacts: List<TachiyomiExtensionArtifact>,
			installed: List<DirectTachiyomiInstalled>,
			loadedSourceIds: Set<Long>,
			preInstalledSources: List<TachiyomiMangaSource>,
			installingPackages: Set<String>,
		): List<SourceCatalogItem> {
			if (!isScopedMode) {
				val allDisabledSources =
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
				val allGrouped =
					allDisabledSources.groupBy { source: MangaSource ->
						val plugin =
							(source as? org.draken.usagi.core.model.PluginMangaSource)?.jarName
								?: (source as? MangaSourceInfo)?.mangaSource as? org.draken.usagi.core.model.PluginMangaSource
						(plugin ?: "") to source.title
					}

				val newLocales =
					buildSet {
						add(null)
						for ((_, variants) in allGrouped) {
							if (variants.size > 1 || variants.any { it.locale.equals("all", true) }) {
								add("all")
							} else {
								variants.forEach { add(canonicalLanguageCode(it.locale)) }
							}
						}
					}
				if (locales.value != newLocales) {
					locales.value = newLocales
				}

				val result = ArrayList<SourceCatalogItem.Source>(allGrouped.size)
				for ((_, variants) in allGrouped) {
					val isMultiLanguage = variants.size > 1 || variants.any { it.locale.equals("all", true) }
					if (filter.locale != null) {
						if (filter.locale.equals("all", true)) {
							if (!isMultiLanguage) continue
						} else {
							if (isMultiLanguage) continue
							val filterLang = canonicalLanguageCode(filter.locale)
							if (variants.none { canonicalLanguageCode(it.locale) == filterLang }) continue
						}
					}
					val preferred =
						variants.firstOrNull { it.locale.equals(Locale.getDefault().language, true) }
							?: variants.firstOrNull { it.locale.equals("en", true) }
							?: variants.first()
					if (!query.isNullOrBlank() && !preferred.title.contains(query, true) && !preferred.name.contains(query, true)) continue
					result.add(SourceCatalogItem.Source(preferred, isMultiLanguage = isMultiLanguage))
				}
				return if (result.isEmpty()) {
					listOf(
						if (query == null) {
							SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.no_manga_sources, R.string.no_manga_sources_catalog_text)
						} else {
							SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_manga_sources_found)
						},
					)
				} else {
					result
				}
			}

			val scoped = scopedArtifacts(artifacts)
			val newLocales =
				buildSet {
					add(null)
					for (artifact in scoped) {
						if (artifact.sources.size > 1 || artifact.sources.any { it.language.equals("all", true) }) {
							add("all")
						} else {
							artifact.sources.forEach { add(canonicalLanguageCode(it.language)) }
						}
					}
				}
			if (locales.value != newLocales) {
				locales.value = newLocales
			}
			val installedByPackage = installed.associateBy { it.packageName }
			val tachiyomiSources = ArrayList<SourceCatalogItem.Tachiyomi>()
			for (artifact in scoped) {
				val isMultiLanguage = artifact.sources.size > 1 || artifact.sources.any { it.language.equals("all", true) }
				if (filter.locale != null) {
					if (filter.locale.equals("all", true)) {
						if (!isMultiLanguage) continue
					} else {
						if (isMultiLanguage) continue
						val filterLang = canonicalLanguageCode(filter.locale)
						if (artifact.sources.none { canonicalLanguageCode(it.language) == filterLang }) continue
					}
				}
				val candidate =
					artifact.sources.firstOrNull { matchesLocale(it.language, Locale.getDefault().language) }
						?: artifact.sources.firstOrNull { it.language.equals("en", true) }
						?: artifact.sources.firstOrNull()
						?: continue
				val type = candidate.contentType
				if (settings.isNsfwContentDisabled && type == ContentType.HENTAI) continue
				if (filter.types.isNotEmpty() && type !in filter.types) continue
				if (!query.isNullOrBlank() && !candidate.name.contains(query, true) && !artifact.name.contains(query, true) && !artifact.packageName.contains(query, true)) continue

				val customPluginName =
					catalogProvider.repositoryName(artifact.repositoryUrl)
						?: DirectTachiyomiPluginMetadata
							.get(artifact.packageName)
				tachiyomiSources.add(
					SourceCatalogItem.Tachiyomi(
						source = candidate,
						artifact = artifact,
						installed = installedByPackage[artifact.packageName],
						isLoaded = candidate.id in loadedSourceIds,
						isPreInstalledApk = preInstalledSources.any { installedSource -> installedSource.matchesCatalogSource(artifact, candidate) },
						isMultiLanguage = isMultiLanguage,
						isInstalling = artifact.packageName in installingPackages,
						customPluginName = customPluginName,
					),
				)
			}
			return if (tachiyomiSources.isEmpty()) {
				listOf(
					if (query == null) {
						SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.no_manga_sources, R.string.no_manga_sources_catalog_text)
					} else {
						SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_manga_sources_found)
					},
				)
			} else {
				tachiyomiSources
			}
		}

		@WorkerThread
		private fun matchesLocale(
			sourceLanguage: String,
			filterLocale: String?,
		): Boolean {
			if (filterLocale == null) return true
			return canonicalLanguageCode(sourceLanguage) == canonicalLanguageCode(filterLocale)
		}

		@WorkerThread
		private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
			if (!isScopedMode) {
				val result = repository.allMangaSources.mapSortedByCount { it.contentType }.toMutableList()
				return if (isNsfwDisabled) result.filterNot { it == ContentType.HENTAI } else result
			}
			val scoped = scopedArtifacts(tachiyomiCatalog.value)
			val result = scoped.flatMap { it.sources }.mapSortedByCount { it.contentType }.toMutableList()
			return if (isNsfwDisabled) result.filterNot { it == ContentType.HENTAI } else result
		}

		companion object {
			const val EXTRA_REPOSITORY_URL = "repository_url"
			const val EXTRA_REPOSITORY_NAME = "repository_name"
		}
	}
