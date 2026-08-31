package org.draken.usagi.core.parser.external.tachiyomi

import android.content.Context
import androidx.collection.LruCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.draken.tsukimix.core.parser.external.ExtRuntime
import org.draken.tsukimix.core.parser.external.model.toManga
import org.draken.tsukimix.core.parser.external.model.toMangaChapter
import org.draken.tsukimix.core.parser.external.model.toMangaPage
import org.draken.tsukimix.core.parser.external.model.toSChapter
import org.draken.tsukimix.core.parser.external.model.toSManga
import org.draken.usagi.R
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
import org.draken.tsukimix.core.parser.external.ExtensionSourceSettings as externalSettings
import org.draken.tsukimix.core.parser.external.model.Manga as ExtensionMangaSource
import org.draken.usagi.core.network.imageproxy.ImageProxyInterceptor as Interceptor

class ExternalMangaRepository(
	context: Context,
	override val source: ExtensionMangaSource,
	cache: MemoryContentCache,
	private val runtime: ExtRuntime? = null,
) : CachingMangaRepository(cache),
	FilterHost {
	private val appContext = context.applicationContext
	val external = source.catalogueSource
	private val filterList =
		suspendLazy(Dispatchers.Default) {
			withContext(Dispatchers.IO) {
				runCatching { external.getFilterList() }.getOrDefault(FilterList())
			}
		}

	private var lastOffset = -1
	private var currentPage = 1
	private val paginationLock = Any()

	@Volatile private var hasMorePages = true

	override val isDynamicFiltersSupported = true

	override suspend fun loadFilterList() =
		withContext(Dispatchers.IO) {
			runCatching { external.getFilterList() }.getOrDefault(FilterList())
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

	override var defaultSortOrder = SortOrder.POPULARITY
		set(value) = Unit

	override val filterCapabilities =
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
		withContext(Dispatchers.Default) {
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
			val q = filter?.query
			val mangasPage =
				try {
					when {
						!q.isNullOrBlank() || filter?.isEmpty() == false -> {
							val f = runCatching { external.getFilterList() }.getOrDefault(FilterList())
							FilterMapper.decode(f, filter)
							external.getSearchManga(page, q ?: "", f)
						}

						(order ?: defaultSortOrder).isLatest() && source.supportsLatest -> {
							external.getLatestUpdates(page)
						}

						else -> {
							external.getPopularManga(page)
						}
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					throw mapException(e)
				}
			hasMorePages = mangasPage.hasNextPage
			val http = external as? HttpSource
			mangasPage.mangas.map { it.toManga(source, fallbackUrl = http?.getMangaUrl(it).orEmpty()) }
		}

	override suspend fun getDetailsImpl(manga: Manga): Manga =
		withContext(Dispatchers.Default) {
			val original = manga.toSManga()
			val update =
				try {
					external.getMangaUpdate(original, emptyList(), fetchDetails = true, fetchChapters = true)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					throw mapException(e)
				}
			val details = update.manga.toManga(source, fallbackUrl = manga.url, fallbackTitle = manga.title)
			val primary = update.chapters.asReversed().mapIndexed { i, c -> c.toMangaChapter(source, details.title, i) }
			val siblings = runtime?.getSiblingSources(source)?.filter { it.sourceId != source.sourceId }.orEmpty()
			val all =
				if (siblings.isEmpty()) {
					primary
				} else {
					primary +
						siblings
							.map { sib ->
								async {
									runCatching {
										sib.catalogueSource
											.getMangaUpdate(original, emptyList(), fetchDetails = false, fetchChapters = true)
											.chapters
											.asReversed()
											.mapIndexed { i, c -> c.toMangaChapter(sib, details.title, i) }
									}.getOrDefault(emptyList())
								}
							}.awaitAll()
							.flatten()
				}
			details.copy(chapters = all, source = source)
		}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> =
		withContext(Dispatchers.Default) {
			val src = (chapter.source as? ExtensionMangaSource)?.catalogueSource ?: external
			val target = chapter.source as? ExtensionMangaSource ?: source
			val list =
				try {
					src.getPageList(chapter.toSChapter())
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					throw mapException(e)
				}
			list.map { p ->
				val res = p.imageUrl ?: (src as? HttpSource)?.getImageUrl(p) ?: p.url
				p.imageUrl = res
				pageCache.put(pageCacheKey(target, res), p)
				p.toMangaPage(target, res)
			}
		}

	override suspend fun getPageUrl(page: MangaPage): String = if (external !is HttpSource) page.url else getPageRequest(page).url.toString()

	override suspend fun getPageRequest(page: MangaPage): Request {
		val src = (page.source as? ExtensionMangaSource)?.catalogueSource ?: external
		val target = page.source as? ExtensionMangaSource ?: source
		val http = src as? HttpSource ?: return super.getPageRequest(page)
		val cp = pageCache[pageCacheKey(target, page.url)] ?: Page(0, page.url, page.url)
		return withContext(Dispatchers.Default) {
			try {
				http
					.getImageRequest(cp)
					.newBuilder()
					.tag(tsuki.model.MangaSource::class.java, target)
					.build()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				throw mapException(e)
			}
		}
	}

	override suspend fun getPageResponse(
		page: MangaPage,
		okHttp: OkHttpClient,
		interceptor: Interceptor,
	): Response {
		val src = (page.source as? ExtensionMangaSource)?.catalogueSource ?: external
		val http = src as? HttpSource ?: return super.getPageResponse(page, okHttp, interceptor)
		val target = page.source as? ExtensionMangaSource ?: source
		val cp = pageCache[pageCacheKey(target, page.url)] ?: Page(0, page.url, page.url)
		return withContext(Dispatchers.Default) {
			try {
				http.getImage(cp)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				throw mapException(e)
			}
		}
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getExternalFilters(): Any = filterList.get()

	fun getBrowserUrl() = externalSettings.browserUrl(appContext, source)

	fun getSettingsPreferences() = externalSettings.preferences(appContext, source)

	fun refreshDomainOverride() = externalSettings.refreshDomainOverride(appContext, source)

	fun isSlowdownEnabled() = externalSettings.isSlowdownEnabled(appContext, source)

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> {
		val http = external as? HttpSource ?: return emptyList()
		if (!http.supportsRelatedMangas || http.disableRelatedMangas) return emptyList()
		return runCatchingCancellable {
			withContext(Dispatchers.IO) {
				http.fetchRelatedMangaList(seed.toSManga()).map { it.toManga(source) }
			}
		}.getOrDefault(emptyList())
	}

	private fun mapException(error: Throwable): IOException {
		val http = external as? HttpSource
		if (http != null && error.hasMsg("cloudflare bypass failed")) {
			val url = externalSettings.browserUrl(appContext, source) ?: http.getHomeUrl()
			return CloudFlareProtectedException(url = url, source = source, headers = http.headers)
		}
		if (error is IOException) return error
		val cause = error.localizedMessage ?: error.message
		val msg =
			if (!cause.isNullOrBlank()) {
				appContext.getString(R.string.plugin_incompatible_with_cause, cause)
			} else {
				appContext.getString(R.string.plugin_incompatible)
			}
		return IOException(msg, error)
	}

	private fun SortOrder.isLatest() = this == SortOrder.NEWEST || this == SortOrder.UPDATED

	private fun Throwable.hasMsg(value: String): Boolean {
		var cur: Throwable? = this
		while (cur != null) {
			if (cur.message?.contains(value, true) == true) return true
			cur = cur.cause
		}
		return false
	}

	private companion object {
		val pageCache = LruCache<String, Page>(500)

		fun pageCacheKey(
			source: ExtensionMangaSource,
			url: String,
		) = "${source.name}:$url"
	}
}
