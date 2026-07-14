package org.draken.usagi.core.parser

import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.Response
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.exceptions.InteractiveActionRequiredException
import org.draken.usagi.core.exceptions.ProxyConfigException
import org.draken.usagi.core.prefs.SourceSettings
import tsuki.MangaParser
import tsuki.MangaParserAuthProvider
import tsuki.config.ConfigKey
import tsuki.exception.AuthRequiredException
import tsuki.model.Favicons
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaSource
import tsuki.model.SortOrder
import tsuki.util.runCatchingCancellable
import tsuki.util.suspendlazy.suspendLazy
import java.io.IOException

class MangaParserRepository(
	private val compoundSource: MangaSource,
	private val parser: MangaParser,
	private val mirrorSwitcher: MirrorSwitcher,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache), Interceptor {

	private val filterOptionsLazy = suspendLazy(Dispatchers.Default) {
		withMirrors {
			try {
				parser.getFilterOptions()
			} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }
		}
	}

	override val source: MangaSource
		get() = compoundSource

	override val sortOrders: Set<SortOrder>
		get() = try {
			parser.availableSortOrders
		} catch (_: LinkageError) { emptySet() }

	override val filterCapabilities: MangaListFilterCapabilities
		get() = try {
			parser.filterCapabilities
		} catch (_: LinkageError) { MangaListFilterCapabilities() }

	override var defaultSortOrder: SortOrder
		get() = getConfig().defaultSortOrder ?: sortOrders.first()
		set(value) {
			getConfig().defaultSortOrder = value
		}

	var domain: String
		get() = try { parser.domain } catch (_: LinkageError) { "" }
		set(value) {
			try {
				getConfig()[parser.configKeyDomain] = value
			} catch (_: LinkageError) {}
		}

	val domains: Array<out String>
		get() = try {
			parser.configKeyDomain.presetValues
		} catch (_: LinkageError) { emptyArray() }

	override fun intercept(chain: Interceptor.Chain): Response = try {
		parser.intercept(chain)
	} catch (e: LinkageError) {
		throw IOException("Parser linkage error", e)
	}

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		return try {
			withMirrors { parser.getList(offset, order ?: defaultSortOrder, filter ?: MangaListFilter.EMPTY) }
		} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }.map { it.copy(source = compoundSource) }
	}

	override suspend fun getPagesImpl(
		chapter: MangaChapter
	): List<MangaPage> = try {
		withMirrors { parser.getPages(chapter) }
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	override suspend fun getPageUrl(page: MangaPage): String = try {
		withMirrors {
			parser.getPageUrl(page).also { result -> check(result.isNotEmpty()) { "Page url is empty" } }
		}
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	override suspend fun getFilterOptions(): MangaListFilterOptions = try {
		filterOptionsLazy.get()
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	suspend fun getFavicons(): Favicons = try {
		withMirrors { parser.getFavicons() }
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = try {
		parser.getRelatedManga(seed).map { it.copy(source = compoundSource) }
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	override suspend fun getDetailsImpl(manga: Manga): Manga = try {
		withMirrors {
			parser.getDetails(manga).let { details ->
				details.copy(
					source = compoundSource,
					chapters = details.chapters?.map { it.copy(source = compoundSource) }
				)
			}
		}
	} catch (e: LinkageError) { throw IOException("Parser linkage error", e) }

	fun getAuthProvider(): MangaParserAuthProvider? = try {
		parser.authorizationProvider
	} catch (_: LinkageError) { null }

	fun getRequestHeaders() = try {
		parser.getRequestHeaders()
	} catch (_: LinkageError) { okhttp3.Headers.headersOf() }

	fun getConfigKeys(): List<ConfigKey<*>> = try {
		ArrayList<ConfigKey<*>>().also { parser.onCreateConfig(it) }
	} catch (_: LinkageError) { emptyList() }

	fun isSlowdownEnabled(): Boolean = try {
		getConfig().isSlowdownEnabled
	} catch (_: Throwable) { false }

	fun getConfig(): SourceSettings = try {
		parser.config as SourceSettings
	} catch (e: LinkageError) { throw RuntimeException("Parser config linkage error", e) }

	private suspend fun <T : Any> withMirrors(block: suspend () -> T): T {
		if (!mirrorSwitcher.isEnabled) {
			return block()
		}
		val initialResult = runCatchingCancellable { block() }
		if (initialResult.isValidResult()) {
			return initialResult.getOrThrow()
		}
		val newResult = mirrorSwitcher.trySwitchMirror(this, block)
		return newResult ?: initialResult.getOrThrow()
	}

	private fun Result<Any>.isValidResult() = fold(
		onSuccess = {
			when (it) {
				is Collection<*> -> it.isNotEmpty()
				else -> true
			}
		},
		onFailure = {
			when (it.cause) {
				is CloudFlareProtectedException,
				is AuthRequiredException,
				is InteractiveActionRequiredException,
				is ProxyConfigException -> true

				else -> false
			}
		},
	)
}
