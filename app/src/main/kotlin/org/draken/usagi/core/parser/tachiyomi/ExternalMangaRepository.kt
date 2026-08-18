package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import androidx.collection.LruCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiRuntime
import org.draken.tsukimix.core.parser.tachiyomi.model.toManga
import org.draken.tsukimix.core.parser.tachiyomi.model.toMangaChapter
import org.draken.tsukimix.core.parser.tachiyomi.model.toMangaPage
import org.draken.tsukimix.core.parser.tachiyomi.model.toSChapter
import org.draken.tsukimix.core.parser.tachiyomi.model.toSManga
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.parser.CachingMangaRepository
import org.draken.usagi.filter.ui.external.FilterHost
import org.draken.usagi.filter.ui.external.FilterMapper
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.SortOrder
import tsuki.util.runCatchingCancellable
import tsuki.util.suspendlazy.suspendLazy
import java.io.IOException
import java.util.EnumSet
import org.draken.tsukimix.core.parser.tachiyomi.ExtensionSourceSettings as externalSettings
import org.draken.tsukimix.core.parser.tachiyomi.model.Manga as TachiyomiMangaSource
import org.draken.usagi.core.network.imageproxy.ImageProxyInterceptor as Interceptor

class ExternalMangaRepository(
	context: Context,
	override val source: TachiyomiMangaSource,
	cache: MemoryContentCache,
	private val runtime: TachiyomiRuntime? = null,
) : CachingMangaRepository(cache),
	FilterHost {
	private val appContext = context.applicationContext
	val external = source.catalogueSource
	private val filterListLazy =
		suspendLazy(Dispatchers.Default) {
			withContext(Dispatchers.IO) {
				try {
					external.getFilterList()
				} catch (_: Throwable) {
					FilterList()
				}
			}
		}

	private var lastOffset = -1
	private var currentPage = 1
	private val paginationLock = Any()

	@Volatile private var hasMorePages = true

	override val isDynamicFiltersSupported: Boolean
		get() = true

	override suspend fun loadFilterList(): FilterList =
		withContext(Dispatchers.IO) {
			try {
				external.getFilterList()
			} catch (_: Throwable) {
				FilterList()
			}
		}

	init {
		refreshDomainOverride()
	}

	override val sortOrders: Set<SortOrder>
		get() =
			if (source.supportsLatest) {
				EnumSet.of(SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.RELEVANCE)
			} else {
				EnumSet.of(SortOrder.POPULARITY, SortOrder.RELEVANCE)
			}

	override var defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY
		set(value) = Unit

	override val filterCapabilities: MangaListFilterCapabilities
		get() =
			MangaListFilterCapabilities(
				isSearchSupported = true,
				isMultipleTagsSupported = true,
				isSearchWithFiltersSupported = true,
			)

	override suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: MangaListFilter?,
	): List<Manga> =
		withContext(Dispatchers.IO) {
			val page =
				synchronized(paginationLock) {
					if (offset == 0) {
						currentPage = 1
						lastOffset = 0
						hasMorePages = true
					} else if (offset > lastOffset) {
						if (!hasMorePages) return@withContext emptyList()
						currentPage++
						lastOffset = offset
					}
					currentPage
				}

			val query = filter?.query
			val mangasPage =
				try {
					when {
						!query.isNullOrBlank() || filter?.isEmpty() == false -> {
							val mihonFilters =
								try {
									external.getFilterList()
								} catch (_: Throwable) {
									FilterList()
								}
							FilterMapper.decode(mihonFilters, filter)
							external.getSearchManga(page, query ?: "", mihonFilters)
						}

						(order ?: defaultSortOrder).isLatest() && source.supportsLatest -> {
							external.getLatestUpdates(page)
						}

						else -> {
							external.getPopularManga(page)
						}
					}
				} catch (e: Throwable) {
					throw mapException(e)
				}
			hasMorePages = mangasPage.hasNextPage
			val httpSource = external as? HttpSource
			mangasPage.mangas.map { sManga ->
				sManga.toManga(
					source = source,
					fallbackUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
				)
			}
		}

	override suspend fun getDetailsImpl(manga: Manga): Manga =
		withContext(Dispatchers.IO) {
			val original = manga.toSManga()
			val update =
				try {
					external.getMangaUpdate(original, emptyList(), fetchDetails = true, fetchChapters = true)
				} catch (e: Throwable) {
					throw mapException(e)
				}
			val details = update.manga.toManga(source, fallbackUrl = manga.url, fallbackTitle = manga.title)
			val primaryChapters =
				update.chapters.asReversed().mapIndexed { index, chapter ->
					chapter.toMangaChapter(source, details.title, index)
				}
			val siblings = runtime?.getSiblingSources(source)?.filter { it.sourceId != source.sourceId }.orEmpty()
			val allChapters =
				if (siblings.isEmpty()) {
					primaryChapters
				} else {
					val siblingChapters =
						siblings
							.map { sibling ->
								async {
									runCatching {
										val siblingUpdate =
											sibling.catalogueSource.getMangaUpdate(
												original,
												emptyList(),
												fetchDetails = false,
												fetchChapters = true,
											)
										siblingUpdate.chapters.asReversed().mapIndexed { index, chapter ->
											chapter.toMangaChapter(sibling, details.title, index)
										}
									}.getOrDefault(emptyList())
								}
							}.awaitAll()
							.flatten()
					primaryChapters + siblingChapters
				}
			details.copy(
				chapters = allChapters,
				source = source,
			)
		}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> =
		withContext(Dispatchers.IO) {
			val chapterSource = (chapter.source as? TachiyomiMangaSource)?.catalogueSource ?: external
			val targetSource = chapter.source as? TachiyomiMangaSource ?: source
			val pageList =
				try {
					chapterSource.getPageList(chapter.toSChapter())
				} catch (e: Throwable) {
					throw mapException(e)
				}
			pageList.map { page ->
				val resolved =
					page.imageUrl
						?: (chapterSource as? HttpSource)?.getImageUrl(page)
						?: page.url
				page.imageUrl = resolved
				pageCache.put(pageCacheKey(targetSource, resolved), page)
				page.toMangaPage(targetSource, resolved)
			}
		}

	override suspend fun getPageUrl(page: MangaPage): String {
		if (external !is HttpSource) return page.url
		return getPageRequest(page).url.toString()
	}

	override suspend fun getPageRequest(page: MangaPage): Request {
		val pageSource = (page.source as? TachiyomiMangaSource)?.catalogueSource ?: external
		val targetSource = page.source as? TachiyomiMangaSource ?: source
		val httpSource = pageSource as? HttpSource ?: return super.getPageRequest(page)
		val tachiyomiPage =
			pageCache[pageCacheKey(targetSource, page.url)]
				?: Page(index = 0, url = page.url, imageUrl = page.url)
		return withContext(Dispatchers.IO) {
			val imageRequest =
				try {
					httpSource.getImageRequest(tachiyomiPage)
				} catch (e: Throwable) {
					throw mapException(e)
				}
			imageRequest
				.newBuilder()
				.tag(tsuki.model.MangaSource::class.java, targetSource)
				.build()
		}
	}

	override suspend fun getPageResponse(
		page: MangaPage,
		okHttp: OkHttpClient,
		interceptor: Interceptor,
	): Response {
		val pageSource = (page.source as? TachiyomiMangaSource)?.catalogueSource ?: external
		val targetSource = page.source as? TachiyomiMangaSource ?: source
		val httpSource = pageSource as? HttpSource ?: return super.getPageResponse(page, okHttp, interceptor)
		val tachiyomiPage =
			pageCache[pageCacheKey(targetSource, page.url)]
				?: Page(index = 0, url = page.url, imageUrl = page.url)
		return withContext(Dispatchers.IO) {
			try {
				httpSource.getImage(tachiyomiPage)
			} catch (e: Throwable) {
				throw mapException(e)
			}
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getExternalFilters(): Any = filterListLazy.get()

	fun getBrowserUrl(): String? = externalSettings.browserUrl(appContext, source)

	fun getSettingsPreferences() = externalSettings.preferences(appContext, source)

	fun refreshDomainOverride() {
		externalSettings.refreshDomainOverride(appContext, source)
	}

	fun isSlowdownEnabled(): Boolean = externalSettings.isSlowdownEnabled(appContext, source)

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> {
		val httpSource = external as? HttpSource ?: return emptyList()
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

	private fun mapException(error: Throwable): IOException {
		val httpSource = external as? HttpSource
		if (httpSource != null && error.hasMessage("cloudflare bypass failed")) {
			return CloudFlareProtectedException(
				url = externalSettings.browserUrl(appContext, source) ?: httpSource.getHomeUrl(),
				source = source,
				headers = httpSource.headers,
			)
		}
		if (error is IOException) return error
		val causeMessage = error.localizedMessage ?: error.message
		val message =
			if (!causeMessage.isNullOrBlank()) {
				appContext.getString(org.draken.usagi.R.string.plugin_incompatible_with_cause, causeMessage)
			} else {
				appContext.getString(org.draken.usagi.R.string.plugin_incompatible)
			}
		return IOException(message, error)
	}

	private fun SortOrder.isLatest(): Boolean = this == SortOrder.NEWEST || this == SortOrder.UPDATED

	private fun Throwable.hasMessage(value: String): Boolean {
		var current: Throwable? = this
		while (current != null) {
			if (current.message?.contains(value, true) == true) return true
			current = current.cause
		}
		return false
	}

	private companion object {
		val pageCache = LruCache<String, Page>(500)

		fun pageCacheKey(
			source: TachiyomiMangaSource,
			url: String,
		): String = "${source.name}:$url"
	}
}
