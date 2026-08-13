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
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiExtensionManager
import org.draken.usagi.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.core.parser.tachiyomi.TachiyomiContentRating
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionArtifact
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionCatalogProvider
import org.draken.usagi.core.parser.tachiyomi.TachiyomiRuntime
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
		private val searchQuery = MutableStateFlow<String?>(null)

		val locales: Set<String?>
			get() =
				buildSet {
					addAll(repository.allMangaSources.map { it.locale })
					addAll(tachiyomiCatalog.value.flatMap { artifact -> artifact.sources.map { it.language } })
					add(null)
				}

		val appliedFilter =
			MutableStateFlow(
				SourcesCatalogFilter(
					types = emptySet(),
					locale = Locale.getDefault().language.takeIf { it in locales },
					isNewOnly = false,
					plugin = null,
				),
			)

		val hasNewSources =
			repository
				.observeHasNewSources()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

		val plugins: List<String>
			get() =
				buildSet {
					addAll(
						repository.allMangaSources.mapNotNull {
							(
								it as? org.draken.usagi.core.model.PluginMangaSource
									?: (it as? MangaSourceInfo)?.mangaSource as? org.draken.usagi.core.model.PluginMangaSource
							)?.jarName
						},
					)
					addAll(tachiyomiCatalog.value.map { it.name })
					addAll(tachiyomiCatalog.value.map { it.packageName })
				}.sorted()

		val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

		val content: StateFlow<List<ListModel>> =
			combine(
				searchQuery,
				appliedFilter,
				db.invalidationTracker.createFlow(TABLE_SOURCES),
				tachiyomiCatalog,
				directManager.installed,
			) { query, filter, _, artifacts, installed ->
				buildSourcesList(filter, query, artifacts, installed)
			}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		init {
			repository.clearNewSourcesBadge()
			launchJob(Dispatchers.Default) {
				runCatching { tachiyomiRuntime.ensureReady() }

				tachiyomiCatalog.value = catalogProvider.loadSaved()
				contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
			}
		}

		fun performSearch(query: String?) {
			searchQuery.value = query?.trim()
		}

		fun setLocale(value: String?) {
			appliedFilter.value = appliedFilter.value.copy(locale = value)
		}

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
				val installed = directManager.install(item.artifact)
				if (installed) tachiyomiRuntime.ensureReady(forceRefresh = true)

				installed
			}.getOrDefault(false)

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
							val rating = source.contentRating.takeUnless { it == TachiyomiContentRating.UNSPECIFIED } ?: artifact.contentRating
							val type = if (rating.isNsfw) ContentType.HENTAI else ContentType.MANGA
							if (settings.isNsfwContentDisabled && type == ContentType.HENTAI) return@mapNotNull null
							if (filter.locale != null && source.language != filter.locale) return@mapNotNull null
							if (filter.types.isNotEmpty() && type !in filter.types) return@mapNotNull null
							if (filter.plugin != null && artifact.packageName != filter.plugin && artifact.name != filter.plugin) return@mapNotNull null
							if (!query.isNullOrBlank() && !source.name.contains(query, true) && !artifact.name.contains(query, true) && !artifact.packageName.contains(query, true)) return@mapNotNull null
							SourceCatalogItem.Tachiyomi(source, artifact, installedByPackage[artifact.packageName])
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
		private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
			val result = repository.allMangaSources.mapSortedByCount { it.contentType }.toMutableList()
			if (tachiyomiCatalog.value.any { artifact -> artifact.sources.any { source -> (source.contentRating.takeUnless { it == TachiyomiContentRating.UNSPECIFIED } ?: artifact.contentRating).isNsfw.not() } } && ContentType.MANGA !in result) result += ContentType.MANGA
			if (!isNsfwDisabled && tachiyomiCatalog.value.any { artifact -> artifact.sources.any { source -> (source.contentRating.takeUnless { it == TachiyomiContentRating.UNSPECIFIED } ?: artifact.contentRating).isNsfw } } && ContentType.HENTAI !in result) result += ContentType.HENTAI
			return result
		}
	}
