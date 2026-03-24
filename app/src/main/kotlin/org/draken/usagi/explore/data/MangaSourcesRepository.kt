package org.draken.usagi.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.draken.usagi.BuildConfig
import org.draken.usagi.core.LocalizedAppContext
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.dao.MangaSourcesDao
import org.draken.usagi.core.db.entity.MangaSourceEntity
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.model.isNsfw
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.ui.util.ReversibleHandle
import org.draken.usagi.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {

	private var assimilatedVersion = -1
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	val allMangaSources: List<MangaSource>
		get() = MangaSourceRegistry.sources

	suspend fun getEnabledSources(): List<MangaSource> {
		assimilateNewSources()
		val order = settings.sourcesSortOrder
		return dao.findAll(!settings.isAllSourcesEnabled, order).toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = order,
			hideBrokenSources = settings.isBrokenSourcesHidden,
		).let { enabled ->
			val external = getExternalSources()
			val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
			list
		}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		assimilateNewSources()
		val skipNsfw = settings.isNsfwContentDisabled
		val hideBroken = settings.isBrokenSourcesHidden
		return dao.findAllPinned().mapNotNullToSet {
			it.source.toMangaSourceOrNull()?.takeUnless { x ->
				(skipNsfw && x.isNsfw()) || (hideBroken && x.isBroken)
			}
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		assimilateNewSources()
		return dao.findLastUsed(limit).toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = null,
			hideBrokenSources = settings.isBrokenSourcesHidden,
		)
	}

	suspend fun getDisabledSources(): Set<MangaSource> {
		assimilateNewSources()
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val result = allMangaSources.toMutableSet()
		if (settings.isNsfwContentDisabled) {
			result.removeAll { it.isNsfw() }
		}
		if (settings.isBrokenSourcesHidden) {
			result.removeAll { it.isBroken }
		}
		val enabled = dao.findAllEnabledNames()
		for (name in enabled) {
			val source = name.toMangaSourceOrNull() ?: continue
			result.remove(source)
		}
		return result
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		sortOrder: SourcesSortOrder?,
	): List<MangaSource> {
		assimilateNewSources()
		val entities = dao.findAll().toMutableList()
		val hideBrokenSources = settings.isBrokenSourcesHidden
		if (isDisabledOnly && !settings.isAllSourcesEnabled) {
			entities.removeAll { it.isEnabled }
		}
		if (isNewOnly) {
			entities.retainAll { it.addedIn == BuildConfig.VERSION_CODE }
		}
		val sources = entities.toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = sortOrder,
			hideBrokenSources = hideBrokenSources,
		).run {
			mapNotNullTo(ArrayList(size)) { it.mangaSource }
		}
		if (locale != null) {
			sources.retainAll { it.locale == locale }
		}
		if (excludeBroken && !hideBrokenSources) {
			sources.removeAll { it.isBroken }
		}
		if (types.isNotEmpty()) {
			sources.retainAll { it.contentType in types }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.getTitle(context).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
			}
		}
		return sources
	}

	private val registryUpdates: Flow<Unit>
		get() = MangaSourceRegistry.updates.onStart { emit(Unit) }

	fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
		return registryUpdates.flatMapLatest {
            assimilateNewSources()
            dao.observeIsEnabled(source.name)
        }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return registryUpdates.flatMapLatest {
            assimilateNewSources()
            combine(
                observeIsNsfwDisabled(),
                observeHideBrokenSources(),
                observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
                    dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
                },
            ) { skipNsfw, hideBroken, sources ->
                sources.count {
                    it.source.toMangaSourceOrNull()?.let { s ->
                        (!skipNsfw || !s.isNsfw()) && (!hideBroken || !s.isBroken)
                    } == true
                }
            }
        }.distinctUntilChanged()
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return registryUpdates.flatMapLatest {
            assimilateNewSources()
            combine(
                observeIsNsfwDisabled(),
                observeHideBrokenSources(),
                observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
                    dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
                },
            ) { skipNsfw, hideBroken, enabledSources ->
                val enabled = enabledSources.mapToSet { it.source }
                allMangaSources.count { x ->
                    x.name !in enabled && (!skipNsfw || !x.isNsfw()) && (!hideBroken || !x.isBroken)
                }
            }
        }.distinctUntilChanged()
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = registryUpdates.flatMapLatest {
        assimilateNewSources()
        combine(
            observeIsNsfwDisabled(),
            observeHideBrokenSources(),
            observeAllEnabled(),
            observeSortOrder(),
        ) { skipNsfw, hideBroken, allEnabled, order ->
            dao.observeAll(!allEnabled, order).map {
                it.toSources(skipNsfw, order, hideBroken)
            }
        }.flattenLatest()
    }.combine(observeExternalSources()) { enabled, external ->
        val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
        external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
        list.addAll(enabled)
        list
    }

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = registryUpdates.flatMapLatest {
        assimilateNewSources()
        dao.observeAll().map { entities ->
            val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
            for (entity in entities) {
                val source = entity.source.toMangaSourceOrNull() ?: continue
                if (source in allMangaSources) {
                    result.add(source to entity.isEnabled)
                }
            }
            result
        }
    }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		db.withTransaction {
			assimilateNewSources()
			for (s in allMangaSources) {
				dao.setEnabled(s.name, s in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateNewSources()
			dao.disableAllSources()
		}
	}

	suspend fun setPositions(sources: List<MangaSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = registryUpdates.flatMapLatest {
        assimilateNewSources()
        observeIsNsfwDisabled().map { skipNsfw ->
            val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(
                skipNsfwSources = skipNsfw,
                sortOrder = null,
                hideBrokenSources = settings.isBrokenSourcesHidden,
            )
            sources.isNotEmpty() && sources.size != allMangaSources.size
        }
    }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = registryUpdates.flatMapLatest {
        assimilateNewSources()
        combine(
            settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
            observeIsNsfwDisabled(),
            observeHideBrokenSources(),
        ) { version, skipNsfw, hideBroken ->
            if (version < BuildConfig.VERSION_CODE) {
                val sources = dao.findAllFromVersion(version).toSources(
                    skipNsfwSources = skipNsfw,
                    sortOrder = null,
                    hideBrokenSources = hideBroken,
                )
                sources.isNotEmpty()
            } else {
                false
            }
        }
    }

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	private suspend fun assimilateNewSources(): Boolean {
		val currentVersion = MangaSourceRegistry.version
		if (assimilatedVersion == currentVersion) {
			return false
		}
		assimilatedVersion = currentVersion
		val new = getNewSources()
		if (new.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setEnabled(sources.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setEnabled(source.name, isEnabled)
			}
		}
	}

	private suspend fun getNewSources(): MutableSet<out MangaSource> {
		val entities = dao.findAll()
		val result = allMangaSources.toMutableSet()
		for (e in entities) {
			result.remove(e.source.toMangaSourceOrNull() ?: continue)
		}
		return result
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setPinned(sources.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalMangaSource> = context.packageManager.queryIntentContentProviders(
		Intent("app.kotatsu.parser.PROVIDE_MANGA"), 0,
	).map { resolveInfo ->
		ExternalMangaSource(
			packageName = resolveInfo.providerInfo.packageName,
			authority = resolveInfo.providerInfo.authority,
		)
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
		hideBrokenSources: Boolean,
	): MutableList<MangaSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<MangaSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			if (hideBrokenSources && source.isBroken) {
				continue
			}
			result.add(
				MangaSourceInfo(
					mangaSource = source,
					isEnabled = entity.isEnabled || isAllEnabled,
					isPinned = entity.isPinned,
				),
			)
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeHideBrokenSources() = settings.observeAsFlow(AppSettings.KEY_SOURCES_HIDE_BROKEN) {
		isBrokenSourcesHidden
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}

	private var cachedSourcesVersion = -1
	private var sourcesMap = emptyMap<String, MangaSource>()

	private fun getSourcesMap(): Map<String, MangaSource> {
		val currentVersion = MangaSourceRegistry.version
		if (cachedSourcesVersion != currentVersion) {
			sourcesMap = MangaSourceRegistry.sources.associateBy { it.name }
			cachedSourcesVersion = currentVersion
		}
		return sourcesMap
	}

	private fun String.toMangaSourceOrNull(): MangaSource? = getSourcesMap()[this]
}
