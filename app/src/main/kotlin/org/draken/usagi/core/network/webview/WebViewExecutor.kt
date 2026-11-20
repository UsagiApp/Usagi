package org.draken.usagi.core.network.webview

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AndroidRuntimeException
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.exceptions.CloudFlareException
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.proxy.ProxyProvider
import org.draken.usagi.core.parser.MangaParserRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.ext.configureForParser
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.sanitizeHeaderValue
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import tsuki.webview.InterceptedRequest
import tsuki.webview.InterceptionConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class WebViewExecutor
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		private val proxyProvider: ProxyProvider,
		private val cookieJar: MutableCookieJar,
		private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
	) {
		private var webViewCached: WeakReference<WebView>? = null
		private val mutex = Mutex()

		val defaultUserAgent: String? by lazy {
			try {
				WebSettings.getDefaultUserAgent(context)
			} catch (e: AndroidRuntimeException) {
				e.printStackTraceDebug()
				// Probably WebView is not available
				null
			}
		}

		suspend fun evaluateJs(
			baseUrl: String?,
			script: String,
		): String? =
			mutex.withLock {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						if (!baseUrl.isNullOrEmpty()) {
							suspendCoroutine { cont ->
								webView.webViewClient = ContinuationResumeWebViewClient(cont)
								webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
							}
						}
						suspendCoroutine { cont ->
							webView.evaluateJavascript(script) { result ->
								cont.resume(result?.takeUnless { it == "null" })
							}
						}
					} finally {
						webView.reset()
					}
				}
			}

		suspend fun interceptRequests(
			url: String,
			config: InterceptionConfig,
		): List<InterceptedRequest> {
			val parsedUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Only http/https URLs are supported")
			val timeoutMs = config.timeoutMs.coerceAtMost(MAX_INTERCEPT_TIMEOUT_MS)
			val maxRequests = config.maxRequests.coerceAtMost(MAX_INTERCEPT_REQUESTS)
			return mutex.withLock {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					val captured = mutableListOf<InterceptedRequest>()
					var scriptHandler: ScriptHandler? = null
					try {
						config.userAgent?.sanitizeHeaderValue()?.takeIf { it.isNotBlank() }?.let {
							webView.settings.userAgentString = it
						}
						if (config.blockImages) {
							webView.settings.loadsImagesAutomatically = false
							webView.settings.blockNetworkImage = true
						}
						val script = config.pageScript?.takeIf { it.isNotBlank() }
						if (script != null &&
							config.injectOnStart &&
							WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
						) {
							scriptHandler =
								WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginsFor(parsedUrl))
						}
						withTimeoutOrNull(timeoutMs.milliseconds) {
							suspendCancellableCoroutine { cont ->
								val completed = AtomicBoolean(false)
								webView.webViewClient =
									InterceptionWebViewClient(
										config = config,
										maxRequests = maxRequests,
										script = script,
										documentStartAttached = scriptHandler != null,
										captured = captured,
									) {
										if (completed.compareAndSet(false, true) && cont.isActive) {
											cont.resume(Unit)
										}
									}
								webView.loadUrl(parsedUrl.toString())
							}
						}
						synchronized(captured) { captured.toList() }
					} finally {
						if (scriptHandler != null &&
							WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
						) {
							scriptHandler.remove()
						}
						if (config.blockImages) {
							webView.settings.blockNetworkImage = false
							webView.settings.loadsImagesAutomatically = true
						}
						webView.reset()
					}
				}
			}
		}

		suspend fun tryResolveCaptcha(
			exception: CloudFlareException,
			timeout: Long,
		): Boolean =
			mutex.withLock {
				runCatchingCancellable {
					withContext(Dispatchers.Main.immediate) {
						val webView = obtainWebView()
						try {
							exception.source.getUserAgent()?.let {
								webView.settings.userAgentString = it
							}
							withTimeout(timeout) {
								suspendCancellableCoroutine { cont ->
									webView.webViewClient =
										CaptchaContinuationClient(
											cookieJar = cookieJar,
											targetUrl = exception.url,
											continuation = cont,
										)
									webView.loadUrl(exception.url)
								}
							}
						} finally {
							webView.reset()
						}
					}
				}.onFailure { e ->
					exception.addSuppressed(e)
					e.printStackTraceDebug()
				}.isSuccess
			}

		private suspend fun obtainWebView(): WebView {
			webViewCached?.get()?.let {
				return it
			}
			return withContext(Dispatchers.Main.immediate) {
				webViewCached?.get()?.let {
					return@withContext it
				}
				WebView(context).also {
					it.configureForParser(null)
					webViewCached = WeakReference(it)
					proxyProvider.applyWebViewConfig()
					it.onResume()
					it.resumeTimers()
				}
			}
		}

		private fun MangaSource.getUserAgent(): String? {
			val repository = mangaRepositoryFactoryProvider.get().create(this) as? MangaParserRepository
			return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		}

		@MainThread
		private fun WebView.reset() {
			stopLoading()
			webViewClient = WebViewClient()
			settings.userAgentString = defaultUserAgent
			loadDataWithBaseURL(null, " ", "text/html", null, null)
			clearHistory()
		}

		private fun allowedOriginsFor(httpUrl: HttpUrl): Set<String> {
			val host = httpUrl.host
			val port = httpUrl.port
			val isDefaultPort = (httpUrl.scheme == "https" && port == 443) || (httpUrl.scheme == "http" && port == 80)
			if (!isDefaultPort) {
				return setOf("${httpUrl.scheme}://$host:$port")
			}
			val isDomainName = !host.contains(':') && !host.all { it.isDigit() || it == '.' }
			return if (isDomainName) {
				setOf("${httpUrl.scheme}://$host", "${httpUrl.scheme}://*.$host")
			} else {
				setOf("${httpUrl.scheme}://$host")
			}
		}

		private class InterceptionWebViewClient(
			private val config: InterceptionConfig,
			private val maxRequests: Int,
			private val script: String?,
			private val documentStartAttached: Boolean,
			private val captured: MutableList<InterceptedRequest>,
			private val onLimitReached: () -> Unit,
		) : WebViewClient() {
			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(
				view: WebView?,
				url: String?,
			): Boolean = url == null || handleUrlOverride(url, "GET", emptyMap())

			@RequiresApi(Build.VERSION_CODES.N)
			override fun shouldOverrideUrlLoading(
				view: WebView?,
				request: WebResourceRequest?,
			): Boolean {
				val req = request ?: return false
				return handleUrlOverride(
					url = req.url.toString(),
					method = req.method ?: "GET",
					headers = req.requestHeaders.orEmpty(),
				)
			}

			@WorkerThread
			override fun shouldInterceptRequest(
				view: WebView?,
				request: WebResourceRequest?,
			): WebResourceResponse? {
				val req = request ?: return null
				captureIfMatches(
					url = req.url.toString(),
					method = req.method ?: "GET",
					headers = req.requestHeaders.orEmpty(),
				)
				return super.shouldInterceptRequest(view, request)
			}

			override fun onPageStarted(
				view: WebView?,
				url: String?,
				favicon: Bitmap?,
			) {
				if (script != null && config.injectOnStart && !documentStartAttached) {
					view?.evaluateJavascript(script, null)
				}
			}

			override fun onPageFinished(
				view: WebView?,
				url: String?,
			) {
				if (script != null && !config.injectOnStart) {
					view?.evaluateJavascript(script, null)
				}
			}

			private fun handleUrlOverride(
				url: String,
				method: String,
				headers: Map<String, String>,
			): Boolean {
				if (url.toHttpUrlOrNull() == null) {
					return true
				}
				if (config.urlPattern == null) return false
				return captureIfMatches(url, method, headers)
			}

			private fun captureIfMatches(
				url: String,
				method: String,
				headers: Map<String, String>,
			): Boolean {
				val pattern = config.urlPattern
				if (pattern != null && !pattern.containsMatchIn(url)) {
					return false
				}
				val limitReached =
					synchronized(captured) {
						if (captured.size >= maxRequests) {
							true
						} else {
							captured.add(
								InterceptedRequest(
									url = url,
									method = method,
									headers = headers,
									timestamp = System.currentTimeMillis(),
								),
							)
							captured.size >= maxRequests
						}
					}
				if (limitReached) {
					onLimitReached()
				}
				return true
			}
		}

		private companion object {
			const val MAX_INTERCEPT_TIMEOUT_MS = 60_000L
			const val MAX_INTERCEPT_REQUESTS = 500
		}
	}
