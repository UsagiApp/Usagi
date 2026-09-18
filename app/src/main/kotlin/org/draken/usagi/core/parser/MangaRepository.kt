package org.draken.usagi.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.draken.tsukimix.core.parser.external.ExtRuntime
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.model.LocalMangaSource
import org.draken.usagi.core.model.MangaSourceInfo
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.TestMangaSource
import org.draken.usagi.core.model.UnknownMangaSource
import org.draken.usagi.core.model.UnresolvedMangaSource
import org.draken.usagi.core.model.resolve
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.parser.external.ExternalMangaRepository
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.local.data.LocalMangaRepository
import tsuki.MangaLoaderContext
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaSource
import tsuki.model.SortOrder
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import org.draken.tsukimix.core.parser.external.model.Manga as ExternalSource
import org.draken.usagi.core.network.imageproxy.ImageProxyInterceptor as Interceptor
import org.draken.usagi.core.parser.external.tachiyomi.ExternalMangaRepository as ExternalRepository

interface MangaRepository {
	val source: MangaSource

	val sortOrders: Set<SortOrder>

	var defaultSortOrder: SortOrder

	val filterCapabilities: MangaListFilterCapabilities

	suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: MangaListFilter?,
	): List<Manga>

	suspend fun getDetails(manga: Manga): Manga

	suspend fun getPages(chapter: MangaChapter): List<MangaPage>

	suspend fun getPageUrl(page: MangaPage): String

	suspend fun getPageRequest(page: MangaPage): Request = createPageRequest(getPageUrl(page), page.source)

	suspend fun getPageResponse(
		page: MangaPage,
		okHttp: OkHttpClient,
		interceptor: Interceptor,
	): Response = interceptor.interceptPageRequest(getPageRequest(page), okHttp)

	suspend fun getFilterOptions(): MangaListFilterOptions

	suspend fun getExternalFilters(): Any? = null

	suspend fun getRelated(seed: Manga): List<Manga>

	suspend fun find(manga: Manga): Manga? {
		val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	@Singleton
	class Factory
		@Inject
		constructor(
			@ApplicationContext private val context: Context,
			private val localMangaRepository: LocalMangaRepository,
			private val loaderContext: Provider<MangaLoaderContext>,
			private val contentCache: MemoryContentCache,
			private val mirrorSwitcher: Provider<MirrorSwitcher>,
			private val mangaRepository: MangaDynamicRepository,
			private val extRuntime: Provider<ExtRuntime>,
		) {
			private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()
			private var cacheVersion = -1

			@AnyThread
			fun create(source: MangaSource): MangaRepository {
				var target = source.resolve()
				if (target is UnresolvedMangaSource || MangaSourceRegistry.sources.isEmpty()) {
					resolve(target)
					target = source.resolve()
				}

				val currentVersion = MangaSourceRegistry.version
				if (cacheVersion != currentVersion) {
					synchronized(cache) {
						if (cacheVersion != currentVersion) {
							cache.clear()
							cacheVersion = currentVersion
						}
					}
				}

				when (target) {
					is MangaSourceInfo -> return create(target.mangaSource)
					LocalMangaSource -> return localMangaRepository
					UnknownMangaSource -> return EmptyMangaRepository(target)
				}
				cache[target]?.get()?.let { return it }
				return synchronized(cache) {
					cache[target]?.get()?.let { return it }
					val repository = createRepository(target)
					if (repository != null && repository !is EmptyMangaRepository) {
						cache[target] = WeakReference(repository)
						repository
					} else {
						EmptyMangaRepository(target)
					}
				}
			}

			private fun createRepository(source: MangaSource): MangaRepository? =
				when (source) {
					TestMangaSource -> {
						TestMangaRepository(
							loaderContext = loaderContext.get(),
							cache = contentCache,
						)
					}

					is ExternalMangaSource -> {
						if (source.isAvailable(context)) {
							ExternalMangaRepository(
								contentResolver = context.contentResolver,
								source = source,
								cache = contentCache,
							)
						} else {
							EmptyMangaRepository(source)
						}
					}

					is ExternalSource -> {
						try {
							ExternalRepository(
								context = context,
								source = source,
								cache = contentCache,
								runtime = extRuntime.get(),
							)
						} catch (_: Throwable) {
							EmptyMangaRepository(source)
						}
					}

					else -> {
						try {
							MangaParserRepository(
								compoundSource = source,
								parser = loaderContext.get().newParserInstance(source),
								cache = contentCache,
								mirrorSwitcher = mirrorSwitcher.get(),
							)
						} catch (_: Throwable) {
							EmptyMangaRepository(source)
						}
					}
				}

			private fun resolve(target: MangaSource? = null) =
				synchronized(this) {
					if (MangaSourceRegistry.sources.isEmpty()) {
						runCatching { mangaRepository.load(mangaRepository.getDir()) }
					}
					if (target is UnresolvedMangaSource && target.name.startsWith("EXTERNAL")) {
						runCatching { kotlinx.coroutines.runBlocking { extRuntime.get().ensureReady() } }
					}
				}
		}

	companion object {
		fun createPageRequest(
			pageUrl: String,
			mangaSource: MangaSource,
		) = Request
			.Builder()
			.url(pageUrl)
			.get()
			.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.tag(MangaSource::class.java, mangaSource)
			.build()
	}
}
