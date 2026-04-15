package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.usagi.core.model.PluginSourceKeyNormalizer
import org.draken.usagi.core.model.TachiyomiPluginSource
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoIndex
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoSource
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoStore
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.mapSortedByCount
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.explore.data.SourcesSortOrder
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import java.io.File
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	private val repository: MangaSourcesRepository,
	private val db: MangaDatabase,
	settings: AppSettings,
	@param:ApplicationContext private val context: Context,
	@param:BaseHttpClient private val okHttpClient: OkHttpClient,
	private val savedFiltersRepository: SavedFiltersRepository,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	private val localesCache = repository.allMangaSources.mapTo(LinkedHashSet<String?>()) { it.locale }
	val locales: Set<String?>
		get() = localesCache

	private val searchQuery = MutableStateFlow<String?>(null)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = Locale.getDefault().language.takeIf { it in localesCache },
			isNewOnly = false,
			plugin = null,
		),
	)

	val hasNewSources = repository.observeHasNewSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val plugins = MutableStateFlow<List<String>>(emptyList())

	val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		db.invalidationTracker.createFlow(TABLE_SOURCES),
	) { q, f, _ ->
		buildSourcesList(f, q)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	@Volatile
	private var installableCache = emptyList<TachiyomiRepoSource>()

	@Volatile
	private var installableCacheSignature = ""

	@Volatile
	private var installableCacheTimestamp = 0L

	init {
		localesCache.add(null)
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.Default) {
			contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(item: SourceCatalogItem.Source) {
		launchJob(Dispatchers.Default) {
			val installable = item.installableRepoSource
			if (installable != null) {
				val rollback = installTachiyomiSource(installable)
				onActionDone.call(
					ReversibleAction(
						if (rollback == null) R.string.load_failed else R.string.source_enabled,
						rollback,
					),
				)
			} else {
				val rollback = repository.setSourcesEnabled(setOf(item.source), true)
				onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
			}
		}
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	fun setPlugin(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(plugin = value)
	}

	private suspend fun buildSourcesList(filter: SourcesCatalogFilter, query: String?): List<SourceCatalogItem> {
		val installedDisabledSources = repository.queryParserSources(
			isDisabledOnly = true,
			isNewOnly = filter.isNewOnly,
			excludeBroken = false,
			types = filter.types,
			query = query,
			locale = filter.locale,
			plugin = filter.plugin,
			sortOrder = SourcesSortOrder.ALPHABETIC,
		)

		val installableSources = if (filter.isNewOnly) {
			emptyList()
		} else {
			queryInstallableRepoSources(filter, query)
		}

		updateLocalesAndPlugins(installedDisabledSources, installableSources)

		val items = ArrayList<SourceCatalogItem.Source>(installedDisabledSources.size + installableSources.size)
		installedDisabledSources.mapTo(items) { source ->
			SourceCatalogItem.Source(source = source)
		}
		installableSources.mapTo(items) { source ->
			SourceCatalogItem.Source(
				source = InstallableTachiyomiSource(source),
				installableRepoSource = source,
			)
		}
		items.sortBy { it.source.getTitle(context).lowercase(Locale.ROOT) }

		if (items.isNotEmpty()) {
			return items
		}
		return listOf(
			if (query.isNullOrBlank()) {
				SourceCatalogItem.Hint(
					icon = R.drawable.ic_empty_feed,
					title = R.string.no_manga_sources,
					text = R.string.no_manga_sources_catalog_text,
				)
			} else {
				SourceCatalogItem.Hint(
					icon = R.drawable.ic_empty_feed,
					title = R.string.nothing_found,
					text = R.string.no_manga_sources_found,
				)
			},
		)
	}

	private suspend fun installTachiyomiSource(source: TachiyomiRepoSource) = runCatching {
		val pluginsDir = PluginFileLoader.pluginsDir(context)
		val outFile = File(pluginsDir, source.stableApkFileName)

		// Remove outdated files for the same extension package to avoid duplicate loads.
		TachiyomiRepoStore.findInstalledPluginFilesByPackage(context, source.extensionPackageName)
			.asSequence()
			.filter { it != outFile.name }
			.forEach { oldFileName ->
				File(pluginsDir, oldFileName).takeIf { it.exists() }?.delete()
				TachiyomiRepoStore.removeInstalledPluginMeta(context, oldFileName)
			}

		val request = Request.Builder().get().url(source.downloadUrl).build()
		okHttpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) return@runCatching null
			PluginFileLoader.copyFromStream(outFile, response.body.byteStream())
		}

		TachiyomiRepoStore.saveInstalledPluginMeta(
			context = context,
			pluginFileName = outFile.name,
			ownerTag = source.repoOwnerTag,
			indexUrl = source.repoUrl,
			extensionPackageName = source.extensionPackageName,
		)
		TachiyomiRepoStore.cleanupInstalledPluginMeta(
			context = context,
			installedFiles = pluginsDir.listFiles().orEmpty().mapTo(HashSet()) { it.name },
		)

		DynamicParserManager.loadParsersFromDirectory(context, pluginsDir)
		PluginSourceKeyNormalizer.normalize(db, savedFiltersRepository)
		invalidateInstallableCache()
		repository.getDisabledSources()

		val installedSource = MangaSourceRegistry.sources.firstOrNull { runtimeSource ->
			val pluginSource = runtimeSource as? PluginMangaSource ?: return@firstOrNull false
			val delegate = pluginSource.delegate as? TachiyomiPluginSource ?: return@firstOrNull false
			delegate.extensionPackageName == source.extensionPackageName &&
				delegate.sourceId.toString() == source.sourceId
		}
		if (installedSource == null) return@runCatching null
		repository.setSourcesEnabled(setOf(installedSource), true)
	}.getOrNull()

	private suspend fun queryInstallableRepoSources(
		filter: SourcesCatalogFilter,
		query: String?,
	): List<TachiyomiRepoSource> {
		val repos = TachiyomiRepoStore.listRepositories(context)
		if (repos.isEmpty()) return emptyList()
		val remote = getInstallableRepoSources(repos)
		if (remote.isEmpty()) return emptyList()

		val installedKeys = MangaSourceRegistry.sources
			.mapNotNullTo(HashSet()) { source ->
				val pluginSource = source as? PluginMangaSource ?: return@mapNotNullTo null
				val delegate = pluginSource.delegate as? TachiyomiPluginSource ?: return@mapNotNullTo null
				"${delegate.extensionPackageName}:${delegate.sourceId}"
			}

		val loweredQuery = query?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
		return remote.asSequence()
			.filter { it.key !in installedKeys }
			.filter { filter.locale == null || it.sourceLang == filter.locale }
			.filter { filter.plugin == null || it.repoOwnerTag == filter.plugin }
			.filter {
				filter.types.isEmpty() || (if (it.isNsfwSource) ContentType.HENTAI else ContentType.MANGA) in filter.types
			}
			.filter {
				if (loweredQuery == null) true
				else {
					it.displayName.lowercase(Locale.ROOT).contains(loweredQuery) ||
						it.extensionPackageName.lowercase(Locale.ROOT).contains(loweredQuery) ||
						it.repoOwnerTag.lowercase(Locale.ROOT).contains(loweredQuery)
				}
			}
			.sortedBy { it.displayName.lowercase(Locale.ROOT) }
			.toList()
	}

	private suspend fun getInstallableRepoSources(repositories: List<TachiyomiRepository>): List<TachiyomiRepoSource> {
		val signature = repositories.joinToString("|") { "${it.ownerTag}=${it.indexUrl}" }
		val now = System.currentTimeMillis()
		if (
			signature == installableCacheSignature &&
			(now - installableCacheTimestamp) <= INSTALLABLE_CACHE_TTL_MS
		) {
			return installableCache
		}
		val loaded = coroutineScope {
			repositories.map { repo ->
				async {
					fetchRepoSources(repo.indexUrl)
				}
			}.flatMap { it.await() }
		}.distinctBy { it.key }
		if (loaded.isEmpty() && signature == installableCacheSignature && installableCache.isNotEmpty()) {
			return installableCache
		}
		installableCache = loaded
		installableCacheSignature = signature
		installableCacheTimestamp = now
		return loaded
	}

	private suspend fun fetchRepoSources(indexUrl: String): List<TachiyomiRepoSource> {
		return runCatching {
			val request = Request.Builder().get().url(indexUrl).build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return@use emptyList()
				val body = response.body.string()
				if (body.isBlank()) return@use emptyList()
				TachiyomiRepoIndex.parseIndex(indexUrl, body)
			}
		}.getOrElse { emptyList() }
	}

	private fun updateLocalesAndPlugins(
		installedDisabledSources: List<MangaSource>,
		installableSources: List<TachiyomiRepoSource>,
	) {
		installedDisabledSources.mapTo(localesCache) { it.locale }
		installableSources.mapTo(localesCache) { it.sourceLang }
		localesCache.add(null)

		val pluginSet = repository.allMangaSources
			.mapNotNullTo(HashSet()) {
				(it as? PluginMangaSource
					?: (it as? MangaSourceInfo)?.mangaSource as? PluginMangaSource)?.jarName
			}
		TachiyomiRepoStore.listRepositories(context).mapTo(pluginSet) { it.ownerTag }
		val sortedPlugins = pluginSet.sortedBy { it.lowercase(Locale.ROOT) }
		if (plugins.value != sortedPlugins) {
			plugins.value = sortedPlugins
		}
	}

	private fun invalidateInstallableCache() {
		installableCacheTimestamp = 0L
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}

	private companion object {
		const val INSTALLABLE_CACHE_TTL_MS = 5 * 60 * 1000L
	}
}
