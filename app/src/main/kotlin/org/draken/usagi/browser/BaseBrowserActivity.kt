package org.draken.usagi.browser

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.model.MangaSource
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.proxy.ProxyProvider
import org.draken.usagi.core.network.webview.adblock.AdBlock
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.parser.ParserMangaRepository
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.util.ext.configureForParser
import org.draken.usagi.core.util.ext.consumeAll
import org.draken.usagi.databinding.ActivityBrowserBinding
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import javax.inject.Inject

@AndroidEntryPoint
abstract class BaseBrowserActivity : BaseActivity<ActivityBrowserBinding>(), BrowserCallback {

	@Inject
	lateinit var proxyProvider: ProxyProvider

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	@Inject
	lateinit var adBlock: AdBlock

	@Inject
	lateinit var cookieJar: MutableCookieJar

	private lateinit var onBackPressedCallback: WebViewBackPressedCallback

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!setContentViewWebViewSafe { ActivityBrowserBinding.inflate(layoutInflater) }) {
			return
		}
		viewBinding.webView.webChromeClient = ProgressChromeClient(viewBinding.progressBar)
		onBackPressedCallback = WebViewBackPressedCallback(viewBinding.webView)
		onBackPressedDispatcher.addCallback(onBackPressedCallback)

		val mangaSource = MangaSource(intent?.getStringExtra(AppRouter.KEY_SOURCE))
		val repository = mangaRepositoryFactory.create(mangaSource) as? ParserMangaRepository
		val userAgent = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.nullIfEmpty()
			?: repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		viewBinding.webView.configureForParser(userAgent)

		onCreate2(savedInstanceState, mangaSource, repository)
	}

	protected abstract fun onCreate2(
		savedInstanceState: Bundle?,
		source: MangaSource,
		repository: ParserMangaRepository?
	)

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val type = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		val barsInsets = insets.getInsets(type)
		viewBinding.webView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAll(type)
	}

	override fun onPause() {
		viewBinding.webView.onPause()
		super.onPause()
	}

	override fun onResume() {
		super.onResume()
		viewBinding.webView.onResume()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (hasViewBinding()) {
			viewBinding.webView.stopLoading()
			viewBinding.webView.destroy()
		}
	}

	override fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.progressBar.isVisible = isLoading
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		this.title = title
		supportActionBar?.subtitle = subtitle
	}

	override fun onHistoryChanged() {
		onBackPressedCallback.onHistoryChanged()
	}

	override fun onPageFinished(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		lifecycleScope.launch(Dispatchers.Default) {
			syncCookiesFromBrowser(httpUrl)
		}
	}

	private fun syncCookiesFromBrowser(target: HttpUrl) {
		val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
		val cookies = LinkedHashMap<String, Cookie>()
		for (probeUrl in buildCookieProbeUrls(target)) {
			val rawCookie = runCatching { manager.getCookie(probeUrl) }.getOrNull() ?: continue
			rawCookie.split(';')
				.mapNotNull { Cookie.parse(target, it.trim()) }
				.forEach { cookie ->
					cookies[cookie.name + "|" + cookie.path] = cookie
				}
		}
		if (cookies.isNotEmpty()) {
			cookieJar.saveFromResponse(target, cookies.values.toList())
		}
	}

	private fun buildCookieProbeUrls(target: HttpUrl): List<String> {
		val urls = LinkedHashSet<String>(4)
		urls += target.toString()
		urls += "${target.scheme}://${target.host}/"
		val topDomain = target.topPrivateDomain()
		if (!topDomain.isNullOrBlank() && !topDomain.equals(target.host, ignoreCase = true)) {
			urls += "${target.scheme}://$topDomain/"
			urls += "${target.scheme}://www.$topDomain/"
		}
		return urls.toList()
	}
}
