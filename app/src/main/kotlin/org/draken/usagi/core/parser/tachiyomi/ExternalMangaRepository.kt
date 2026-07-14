package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.network.imageproxy.ImageProxyInterceptor
import org.draken.usagi.core.parser.CachingMangaRepository
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiSourceSettings
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import org.draken.tsukimix.core.parser.tachiyomi.model.toManga
import org.draken.tsukimix.core.parser.tachiyomi.model.toMangaChapter
import org.draken.tsukimix.core.parser.tachiyomi.model.toMangaPage
import org.draken.tsukimix.core.parser.tachiyomi.model.toSChapter
import org.draken.tsukimix.core.parser.tachiyomi.model.toSManga
import tsuki.util.runCatchingCancellable
import tsuki.util.suspendlazy.suspendLazy
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.SortOrder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import org.draken.usagi.filter.ui.external.FilterHost
import org.draken.usagi.filter.ui.external.FilterMapper
import java.io.IOException

class ExternalMangaRepository(
	private val context: Context,
	override val source: TachiyomiMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache), FilterHost {
	val tachiyomiSource = source.catalogueSource
	private val filterListLazy = suspendLazy(Dispatchers.Default) {
		withContext(Dispatchers.IO) {
			try {
				tachiyomiSource.getFilterList()
			} catch (_: Throwable) {
				FilterList()
			}
		}
	}

	private var lastOffset = -1
	private var currentPage = 1
	private val paginationLock = Any()
	@Volatile private var hasMorePages = true

	override val supportsDynamicFilters: Boolean
		get() = true

	override suspend fun loadDefaultFilterList(): FilterList = withContext(Dispatchers.IO) {
		try {
			tachiyomiSource.getFilterList()
		} catch (_: Throwable) { FilterList() }
	}

	init {
		refreshDomainOverride()
	}

	override val sortOrders: Set<SortOrder>
		get() = if (source.supportsLatest) {
			EnumSet.of(SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.RELEVANCE)
		} else {
			EnumSet.of(SortOrder.POPULARITY, SortOrder.RELEVANCE)
		}

	override var defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY
		set(value) = Unit

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		val page = synchronized(paginationLock) {
			if (offset == 0) {
				currentPage = 1
				lastOffset = 0
				hasMorePages = true
			} else if (offset > lastOffset) {
				lastOffset = offset
				currentPage += 1
			}
			currentPage
		}

		if (offset > 0 && !hasMorePages) return@withContext emptyList()

		val query = filter?.query?.trim().orEmpty()

		val hasFilters = filter?.let {
			it.query?.isNotBlank() == true || it.tags.isNotEmpty() || it.tagsExclude.isNotEmpty()
		} ?: false

		val mangasPage = try {
			when {
				hasFilters -> {
					val mihonFilters = try {
						tachiyomiSource.getFilterList()
					} catch (_: Throwable) { FilterList() }
					FilterMapper.decode(mihonFilters, filter)
					try {
						tachiyomiSource.getSearchManga(page, query, mihonFilters)
					} catch (e: Throwable) {
						throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
					}
				}
				(order ?: defaultSortOrder).isLatest() && source.supportsLatest -> {
					try {
						tachiyomiSource.getLatestUpdates(page)
					} catch (e: Throwable) {
						throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
					}
				}
				else -> {
					try {
						tachiyomiSource.getPopularManga(page)
					} catch (e: Throwable) {
						throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
					}
				}
			}
		} catch (e: Throwable) {
			throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
		}

		hasMorePages = mangasPage.hasNextPage

		val httpSource = tachiyomiSource as? HttpSource
		mangasPage.mangas.map { sManga ->
			sManga.toManga(
				source = source,
				fallbackUrl = httpSource?.getMangaUrl(sManga).orEmpty()
			)
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		return withContext(Dispatchers.IO) {
			val original = manga.toSManga()
			val update = try {
				tachiyomiSource.getMangaUpdate(original, emptyList(), fetchDetails = true, fetchChapters = true)
			} catch (e: Throwable) {
				throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
			}
			val details = update.manga.toManga(source, fallbackUrl = manga.url, fallbackTitle = manga.title)
			details.copy(
				chapters = update.chapters.asReversed().mapIndexed { index, chapter ->
					chapter.toMangaChapter(source, details.title, index)
				},
				source = source,
			)
		}
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> {
		return withContext(Dispatchers.IO) {
			val pageList = try {
				tachiyomiSource.getPageList(chapter.toSChapter())
			} catch (e: Throwable) {
				throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
			}
			pageList.map { page ->
				val resolved = page.imageUrl
					?: (tachiyomiSource as? HttpSource)?.getImageUrl(page)
					?: page.url
				page.imageUrl = resolved
				pageCache[pageCacheKey(source, resolved)] = page
				page.toMangaPage(source, resolved)
			}
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getPageRequest(page: MangaPage): Request {
		val httpSource = tachiyomiSource as? HttpSource ?: return super.getPageRequest(page)
		val tachiyomiPage = pageCache[pageCacheKey(source, page.url)]
			?: Page(index = 0, url = page.url, imageUrl = page.url)
		return withContext(Dispatchers.IO) {
			val imageRequest = try {
				httpSource.getImageRequest(tachiyomiPage)
			} catch (e: Throwable) {
				throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
			}
			imageRequest.newBuilder()
				.tag(tsuki.model.MangaSource::class.java, source)
				.build()
		}
	}

	override suspend fun getPageResponse(
		page: MangaPage,
		okHttp: OkHttpClient,
		imageProxyInterceptor: ImageProxyInterceptor,
	): Response {
		val httpSource = tachiyomiSource as? HttpSource ?: return super.getPageResponse(page, okHttp, imageProxyInterceptor)
		val tachiyomiPage = pageCache[pageCacheKey(source, page.url)]
			?: Page(index = 0, url = page.url, imageUrl = page.url)
		return withContext(Dispatchers.IO) {
			try {
				httpSource.getImage(tachiyomiPage)
			} catch (e: Throwable) {
				throw e as? IOException ?: IOException(e.message ?: e.toString(), e)
			}
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getExternalFilters(): Any = filterListLazy.get()

	fun getBrowserUrl(): String? = TachiyomiSourceSettings.browserUrl(context, source)

	fun getSettingsPreferences() = TachiyomiSourceSettings.preferences(context, source)

	fun refreshDomainOverride() {
		TachiyomiSourceSettings.refreshDomainOverride(context, source)
	}

	fun isSlowdownEnabled(): Boolean = TachiyomiSourceSettings.isSlowdownEnabled(context, source)

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> {
		val httpSource = tachiyomiSource as? HttpSource ?: return emptyList()
		return if (!httpSource.supportsRelatedMangas || httpSource.disableRelatedMangas) {
			emptyList()
		} else {
			runCatchingCancellable {
				withContext(Dispatchers.IO) {
				httpSource.fetchRelatedMangaList(seed.toSManga()).map { it.toManga(source) }
				}
			}.getOrDefault(emptyList())
		}
	}

	private fun SortOrder.isLatest(): Boolean = this == SortOrder.NEWEST || this == SortOrder.UPDATED

	private companion object {
		val pageCache = ConcurrentHashMap<String, Page>()
		fun pageCacheKey(source: TachiyomiMangaSource, url: String): String = "${source.name}:$url"
	}
}
