package org.draken.usagi.core.network.webview

import android.content.Context
import android.graphics.Bitmap
import android.util.AndroidRuntimeException
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.exceptions.CloudFlareException
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.proxy.ProxyProvider
import org.draken.usagi.core.parser.MangaParserRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.ext.configureForParser
import org.draken.usagi.core.util.ext.printStackTraceDebug
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import tsuki.webview.InterceptedRequest
import tsuki.webview.InterceptionConfig
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
		): List<InterceptedRequest> =
			mutex.withLock {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					val captured = mutableListOf<InterceptedRequest>()
					var scriptHandler: ScriptHandler? = null
					try {
						config.userAgent?.let { webView.settings.userAgentString = it }
						if (config.blockImages) {
							webView.settings.loadsImagesAutomatically = false
							webView.settings.blockNetworkImage = true
						}
						val script = config.pageScript?.takeIf { it.isNotBlank() }
						val useDocumentStart =
							script != null &&
								config.injectOnStart &&
								WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
						if (useDocumentStart) {
							scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginsFor(url))
						}
						withTimeoutOrNull(config.timeoutMs) {
							suspendCancellableCoroutine<Unit> { cont ->
								val completed = AtomicBoolean(false)
								webView.webViewClient =
									InterceptionWebViewClient(
										config = config,
										script = script,
										documentStartAttached = useDocumentStart,
										captured = captured,
									) {
										if (completed.compareAndSet(false, true) && cont.isActive) {
											cont.resume(Unit)
										}
									}
								webView.loadUrl(url)
							}
						}
						synchronized(captured) { captured.toList() }
					} finally {
						scriptHandler?.remove()
						if (config.blockImages) {
							webView.settings.blockNetworkImage = false
							webView.settings.loadsImagesAutomatically = true
						}
						webView.reset()
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

		private fun allowedOriginsFor(url: String): Set<String> {
			val httpUrl = url.toHttpUrlOrNull() ?: return setOf("*")
			val port = httpUrl.port
			val isDefaultPort = (httpUrl.scheme == "https" && port == 443) || (httpUrl.scheme == "http" && port == 80)
			if (!isDefaultPort) {
				return setOf("${httpUrl.scheme}://${httpUrl.host}:$port")
			}
			return setOf("${httpUrl.scheme}://${httpUrl.host}", "${httpUrl.scheme}://*.${httpUrl.host}")
		}

		private class InterceptionWebViewClient(
			private val config: InterceptionConfig,
			private val script: String?,
			private val documentStartAttached: Boolean,
			private val captured: MutableList<InterceptedRequest>,
			private val onLimitReached: () -> Unit,
		) : WebViewClient() {
			override fun shouldOverrideUrlLoading(
				view: WebView?,
				request: WebResourceRequest?,
			): Boolean {
				val req = request ?: return false
				if (config.urlPattern == null) return false
				return captureIfMatches(
					url = req.url.toString(),
					method = req.method ?: "GET",
					headers = req.requestHeaders.orEmpty(),
				)
			}

			override fun shouldInterceptRequest(
				view: WebView?,
				request: WebResourceRequest?,
			): WebResourceResponse? {
				val req = request ?: return null
				val reqUrl = req.url.toString()
				val matched =
					captureIfMatches(
						url = reqUrl,
						method = req.method ?: "GET",
						headers = req.requestHeaders.orEmpty(),
					)
				if (matched && config.urlPattern != null && reqUrl.contains(".intercept/")) {
					return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
				}
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
						if (captured.size >= config.maxRequests) {
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
							captured.size >= config.maxRequests
						}
					}
				if (limitReached) {
					onLimitReached()
				}
				return true
			}
		}
	}
