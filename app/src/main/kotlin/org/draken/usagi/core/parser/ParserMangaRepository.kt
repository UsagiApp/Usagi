package org.draken.usagi.core.parser

import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.draken.usagi.core.cache.MemoryContentCache
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.exceptions.InteractiveActionRequiredException
import org.draken.usagi.core.exceptions.ProxyConfigException
import org.draken.usagi.core.model.isTachiyomiExtensionSource
import org.draken.usagi.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.Locale

class ParserMangaRepository(
	private val parser: MangaParser,
	private val mirrorSwitcher: MirrorSwitcher,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache), Interceptor {

	private val filterOptionsLazy = suspendLazy(Dispatchers.Default) {
		withMirrors {
			parser.getFilterOptions()
		}
	}

	override val source: MangaSource
		get() = parser.source

	override val sortOrders: Set<SortOrder>
		get() = parser.availableSortOrders

	override val filterCapabilities: MangaListFilterCapabilities
		get() = parser.filterCapabilities

	override var defaultSortOrder: SortOrder
		get() = getConfig().defaultSortOrder ?: sortOrders.first()
		set(value) {
			getConfig().defaultSortOrder = value
		}

	var domain: String
		get() = parser.domain
		set(value) {
			getConfig()[parser.configKeyDomain] = value
		}

	val domains: Array<out String>
		get() = parser.configKeyDomain.presetValues

	override fun intercept(chain: Interceptor.Chain): Response = parser.intercept(chain)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		return try {
			withMirrors {
				parser.getList(offset, order ?: defaultSortOrder, filter ?: MangaListFilter.EMPTY)
			}
		} catch (e: Throwable) {
			throw mapTachiyomiCaptchaException(e, null)
		}
	}

	override suspend fun getPagesImpl(
		chapter: MangaChapter
	): List<MangaPage> = try {
		withMirrors {
			parser.getPages(chapter)
		}
	} catch (e: Throwable) {
		throw mapTachiyomiCaptchaException(e, chapter.url)
	}

	override suspend fun getPageUrl(page: MangaPage): String = try {
		withMirrors {
			parser.getPageUrl(page).also { result ->
				check(result.isNotEmpty()) { "Page url is empty" }
			}
		}
	} catch (e: Throwable) {
		throw mapTachiyomiCaptchaException(e, page.url.ifBlank { page.preview.orEmpty() })
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptionsLazy.get()

	suspend fun getFavicons(): Favicons = withMirrors {
		parser.getFavicons()
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = parser.getRelatedManga(seed)

	override suspend fun getDetailsImpl(manga: Manga): Manga = try {
		withMirrors {
			parser.getDetails(manga)
		}
	} catch (e: Throwable) {
		throw mapTachiyomiCaptchaException(e, manga.publicUrl.ifBlank { manga.url })
	}

	fun getAuthProvider(): MangaParserAuthProvider? = parser.authorizationProvider

	fun getRequestHeaders() = parser.getRequestHeaders()

	fun getConfigKeys(): List<ConfigKey<*>> = ArrayList<ConfigKey<*>>().also {
		parser.onCreateConfig(it)
	}

	fun getAvailableMirrors(): List<String> {
		return parser.configKeyDomain.presetValues.toList()
	}

	fun isSlowdownEnabled(): Boolean {
		return getConfig().isSlowdownEnabled
	}

	fun getConfig() = parser.config as SourceSettings

	private fun mapTachiyomiCaptchaException(error: Throwable, rawUrl: String?): Throwable {
		if (!source.isTachiyomiExtensionSource()) return error
		val chain = generateSequence(error) { it.cause }.toList()
		val parseException = chain.filterIsInstance<ParseException>().firstOrNull()
		val signal = chain
			.mapNotNull { throwable ->
				when (throwable) {
					is ParseException -> throwable.shortMessage ?: throwable.message
					else -> throwable.message
				}
			}.firstOrNull { isCaptchaSignal(it) }
			?: return error
		val targetUrl = resolveBrowserUrl(parseException?.url ?: rawUrl) ?: return error
		val headers = runCatching { getRequestHeaders() }.getOrDefault(okhttp3.Headers.Builder().build())
		return CloudFlareProtectedException(
			url = targetUrl,
			source = source,
			headers = headers,
		).also {
			it.addSuppressed(error)
			it.addSuppressed(IllegalStateException(signal))
		}
	}

	private fun isCaptchaSignal(message: String): Boolean {
		val lower = message.lowercase(Locale.ROOT)
		return CAPTCHA_KEYWORDS.any { keyword -> keyword in lower }
	}

	private fun resolveBrowserUrl(rawUrl: String?): String? {
		val candidate = rawUrl?.trim().orEmpty()
		if (candidate.isNotEmpty() && candidate.toHttpUrlOrNull() != null) {
			return candidate
		}
		val host = domain.trim().trimEnd('/').ifBlank { return null }
		val base = if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
		return when {
			candidate.isEmpty() -> "$base/"
			candidate.startsWith("/") -> "$base$candidate"
			else -> "$base/$candidate"
		}
	}

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

	private companion object {
		private val CAPTCHA_KEYWORDS = listOf(
			"cloudflare",
			"turnstile",
			"captcha",
			"verify you are human",
			"just a moment",
			"webview",
		)
	}
}
