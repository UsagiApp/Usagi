package org.draken.usagi.list.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.model.LocalMangaSource
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.SynchronizedSieveCache
import tsuki.model.Manga
import tsuki.model.MangaTag
import tsuki.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val CACHE_SIZE = 256

/**
 * Resolves tags for list items that carry none, so [ExcludedTags] can match manga
 * from sources whose list endpoints do not return tags. Items that already have
 * tags, and all items when the exclusion list is empty, are returned untouched.
 */
@Singleton
class MangaTagsEnricher
	@Inject
	constructor() {
		private val cache = SynchronizedSieveCache<MemoryContentCache.Key, Set<MangaTag>>(CACHE_SIZE)
		private val semaphore = Semaphore(MAX_PARALLELISM)

		suspend fun enrich(
			repository: MangaRepository,
			manga: List<Manga>,
			exclusion: ExcludedTags,
		): List<Manga> {
			// Local items are read from files, resolving their details would parse every file in the list
			if (repository.source is LocalMangaSource) return manga
			if (!exclusion.isNotEmpty) return manga
			val missing = manga.filter { it.tags.isEmpty() }.distinctBy { it.url }
			if (missing.isEmpty()) return manga
			val resolved =
				coroutineScope {
					missing
						.map { item -> async { item.url to resolve(repository, item) } }
						.awaitAll()
				}.toMap()
			return manga.map { item ->
				val tags = if (item.tags.isEmpty()) resolved[item.url] else null
				if (tags != null) item.copy(tags = tags) else item
			}
		}

		private suspend fun resolve(
			repository: MangaRepository,
			manga: Manga,
		): Set<MangaTag>? {
			val key = MemoryContentCache.Key(manga.source, manga.url)
			cache[key]?.let { return it }
			return semaphore.withPermit {
				cache[key]
					?: runCatchingCancellable { repository.getDetails(manga).tags }
						.getOrNull()
						?.also { cache.put(key, it) }
			}
		}
	}
