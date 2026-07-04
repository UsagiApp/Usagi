package org.draken.usagi.core.parser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.annotation.Keep
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.draken.usagi.core.exceptions.InteractiveActionRequiredException
import org.draken.usagi.core.image.BitmapDecoderCompat
import org.draken.usagi.core.network.MangaHttpClient
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.webview.WebViewExecutor
import org.draken.usagi.core.prefs.SourceSettings
import org.draken.usagi.core.util.ext.toList
import org.draken.usagi.core.util.ext.toMimeType
import org.draken.usagi.core.util.ext.use
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.bitmap.Bitmap
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource
import tsuki.network.UserAgents
import tsuki.util.map
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Keep
@Singleton
class MangaLoaderContextImpl @Inject constructor(
	@MangaHttpClient override val httpClient: OkHttpClient,
	override val cookieJar: MutableCookieJar,
	@ApplicationContext private val androidContext: Context,
	private val webViewExecutor: WebViewExecutor,
	private val mangaDynamicRepository: MangaDynamicRepository,
) : MangaLoaderContext() {

	private val jsTimeout = TimeUnit.SECONDS.toMillis(4)

	@Deprecated("Provide a base url")
	@SuppressLint("SetJavaScriptEnabled")
	override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

	override suspend fun evaluateJs(baseUrl: String, script: String): String? = withTimeout(jsTimeout) {
		webViewExecutor.evaluateJs(baseUrl, script)
	}

	override fun getDefaultUserAgent(): String = webViewExecutor.defaultUserAgent ?: UserAgents.FIREFOX_MOBILE

	override fun getParserSources(): List<MangaSource> = org.draken.usagi.core.model.MangaSourceRegistry.sources

	override fun newParserInstance(source: MangaSource): MangaParser =
		mangaDynamicRepository.create(source, this)

	override fun getConfig(source: MangaSource): MangaSourceConfig {
		return SourceSettings(androidContext, source)
	}

	override fun encodeBase64(data: ByteArray): String {
		return Base64.encodeToString(data, Base64.NO_WRAP)
	}

	override fun decodeBase64(data: String): ByteArray {
		return Base64.decode(data, Base64.DEFAULT)
	}

	override fun getPreferredLocales(): List<Locale> {
		return LocaleListCompat.getAdjustedDefault().toList()
	}

	override fun requestBrowserAction(
		parser: MangaParser,
		url: String,
	): Nothing = throw InteractiveActionRequiredException(parserSourceName(parser), url)

	override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
		return response.map { body ->
			BitmapDecoderCompat.decode(body.byteStream(), body.contentType()?.toMimeType(), isMutable = true)
				.use { bitmap ->
					(redraw(BitmapWrapper.create(bitmap)) as BitmapWrapper).use { result ->
						Buffer().also {
							result.compressTo(it.outputStream())
						}.asResponseBody("image/jpeg".toMediaType())
					}
				}
		}
	}

	override fun createBitmap(width: Int, height: Int): Bitmap = BitmapWrapper.create(width, height)

	private fun parserSourceName(parser: MangaParser): String {
		val s = runCatching { parser.javaClass.getMethod("getSource").invoke(parser) }.getOrNull() ?: return "Unknown"
		if (s is String) return s
		if (s is MangaSource) return s.name
		return listOf("getName", "name").firstNotNullOfOrNull { m ->
			runCatching { s.javaClass.getMethod(m).invoke(s) as? String }.getOrNull()?.takeIf { it.isNotBlank() }
		} ?: s.toString()
	}
}
