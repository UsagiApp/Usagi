package org.draken.usagi.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.draken.tsukimix.core.parser.tachiyomi.DirectTachiyomiExtensionManager
import org.draken.tsukimix.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionCatalogProvider
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiRuntime
import org.draken.tsukimix.core.util.canonicalLanguageCode
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.model.MangaSourceInfo
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

@HiltViewModel
class SourcesCatalogViewModel
	@Inject
	constructor(
		private val repository: MangaSourcesRepository,
		db: MangaDatabase,
		private val settings: AppSettings,
		private val directManager: DirectTachiyomiExtensionManager,
		private val tachiyomiRuntime: TachiyomiRuntime,
		private val catalogProvider: TachiyomiExtensionCatalogProvider,
	) : BaseViewModel() {
		val onActionDone = MutableEventFlow<ReversibleAction>()

		private val tachiyomiCatalog = MutableStateFlow<List<TachiyomiExtensionArtifact>>(emptyList())
		private val isInitialLoading = MutableStateFlow(true)
		private val searchQuery = MutableStateFlow<String?>(null)

		val locales: Set<String?>
			get() =
				buildSet {
					addAll(repository.allMangaSources.map { canonicalLanguageCode(it.locale) })
					addAll(tachiyomiCatalog.value.flatMap { artifact -> artifact.sources.map { canonicalLanguageCode(it.language) } })
					add(null)
				}

		val appliedFilter =
			MutableStateFlow(
				SourcesCatalogFilter(
					types = emptySet(),
					locale = canonicalLanguageCode(Locale.getDefault().language).takeIf { it in locales },
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
				buildMap {
					repository.allMangaSources.forEach { source ->
						val plugin =
							(source as? org.draken.usagi.core.model.PluginMangaSource)
								?: (source as? MangaSourceInfo)?.mangaSource as? org.draken.usagi.core.model.PluginMangaSource
						plugin?.let { put(it.jarName, SourceCatalogPlugin(it.jarName, it.jarName.removeSuffix(".jar"))) }
					}
					tachiyomiCatalog
						.value
						.groupBy { it.repositoryUrl }
						.keys
						.forEach { repositoryUrl ->
							put(repositoryUrl, SourceCatalogPlugin(repositoryUrl, catalogProvider.repositoryName(repositoryUrl) ?: tachiyomiPluginLabel(repositoryUrl)))
						}
				}.values.sortedBy { it.label.lowercase(Locale.ROOT) }

		val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

		private val tachiyomiState =
			combine(directManager.installed, tachiyomiRuntime.sources, isInitialLoading) { installed, loaded, loading ->
				Triple(installed, loaded.map { it.sourceId }.toSet(), loading)
			}

		val content: StateFlow<List<ListModel>> =
			combine(
				searchQuery,
				appliedFilter,
				db.invalidationTracker.createFlow(TABLE_SOURCES),
				tachiyomiCatalog,
				tachiyomiState,
			) { query, filter, _, artifacts, (installed, loaded, loading) ->
				if (loading) listOf(LoadingState) else buildSourcesList(filter, query, artifacts, installed, loaded)
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
						contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
					}
				}
			}
		}

		fun performSearch(query: String?) {
			searchQuery.value = query?.trim()
		}

		fun setLocale(value: String?) {
			appliedFilter.value = appliedFilter.value.copy(locale = value)
		}

		fun tachiyomiCatalogError(): String? = catalogProvider.lastLoadError

		fun tachiyomiInstallError(): String? = directManager.lastInstallError

		suspend fun addTachiyomiRepository(input: String): Boolean {
			val artifacts = catalogProvider.load(input)
			if (artifacts.isEmpty()) return false
			catalogProvider.saveRepository(input)
			tachiyomiCatalog.value = (tachiyomiCatalog.value + artifacts).distinctBy { it.packageName }
			contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
			return true
		}

		suspend fun installTachiyomi(item: SourceCatalogItem.Tachiyomi): Boolean =
			runCatching {
				if (item.isInstalled && !item.hasUpdate && !item.isLoaded) {
					val source = tachiyomiRuntime.getSourceById(item.source.id) ?: return@runCatching false
					repository.setSourcesEnabled(setOf(source), true)
					tachiyomiRuntime.ensureReady(forceRefresh = true)
					return@runCatching true
				}
				val installed = directManager.install(item.artifact)
				if (installed) tachiyomiRuntime.ensureReady(forceRefresh = true)

				installed
			}.getOrDefault(false)

		suspend fun getImportedTachiyomiSource(item: SourceCatalogItem.Tachiyomi): MangaSource? {
			if (!item.isInstalled) return null
			return tachiyomiRuntime.getSourceById(item.source.id)
				?: run {
					tachiyomiRuntime.ensureReady()
					tachiyomiRuntime.getSourceById(item.source.id)
				}
		}

		fun addSource(source: MangaSource) {
			launchJob(Dispatchers.Default) {
				val rollback = repository.setSourcesEnabled(setOf(source), true)
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
		): List<SourceCatalogItem> {
			val sources =
				repository.queryParserSources(
					isDisabledOnly = true,
					isNewOnly = filter.isNewOnly,
					excludeBroken = false,
					types = filter.types,
					query = query,
					locale = filter.locale,
					plugin = filter.plugin,
					sortOrder = SourcesSortOrder.ALPHABETIC,
				)
			val tachiyomiSources =
				if (filter.isNewOnly) {
					emptyList()
				} else {
					val installedByPackage = installed.associateBy { it.packageName }
					artifacts.flatMap { artifact ->
						artifact.sources.mapNotNull { source ->
							val type = source.contentType

							if (settings.isNsfwContentDisabled && type == ContentType.HENTAI) return@mapNotNull null
							if (!matchesLocale(source.language, filter.locale)) return@mapNotNull null

							if (filter.types.isNotEmpty() && type !in filter.types) return@mapNotNull null
							if (filter.plugin != null && artifact.repositoryUrl != filter.plugin) return@mapNotNull null

							if (!query.isNullOrBlank() && !source.name.contains(query, true) && !artifact.name.contains(query, true) && !artifact.packageName.contains(query, true)) return@mapNotNull null
							SourceCatalogItem.Tachiyomi(
								source = source,
								artifact = artifact,
								installed = installedByPackage[artifact.packageName],
								isLoaded = source.id in loadedSourceIds,
							)
						}
					}
				}
			val result = ArrayList<SourceCatalogItem>(sources.size + tachiyomiSources.size)
			result.addAll(sources.map { SourceCatalogItem.Source(it) })
			result.addAll(tachiyomiSources)
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

		@WorkerThread
		private fun matchesLocale(
			sourceLanguage: String,
			filterLocale: String?,
		): Boolean {
			if (filterLocale == null) return true
			return canonicalLanguageCode(sourceLanguage) == canonicalLanguageCode(filterLocale)
		}

		private fun tachiyomiPluginLabel(repositoryUrl: String): String {
			val path =
				runCatching {
					java.net
						.URI(repositoryUrl)
						.path
						.trim('/')
						.split('/')
						.filter { it.isNotBlank() }
				}.getOrDefault(emptyList())
			val owner = path.firstOrNull().orEmpty()
			return owner.ifBlank { repositoryUrl } + " (Mihon)"
		}

		@WorkerThread
		private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
			val result = repository.allMangaSources.mapSortedByCount { it.contentType }.toMutableList()
			if (tachiyomiCatalog.value.any { artifact -> artifact.sources.any { source -> source.contentType == ContentType.MANGA } } && ContentType.MANGA !in result) result += ContentType.MANGA
			if (!isNsfwDisabled && tachiyomiCatalog.value.any { artifact -> artifact.sources.any { source -> source.contentType == ContentType.HENTAI } } && ContentType.HENTAI !in result) result += ContentType.HENTAI
			return result
		}
	}
