package org.draken.usagi.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.R
import org.draken.usagi.browser.BaseBrowserActivity
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.exceptions.resolve.CaptchaHandler
import org.draken.usagi.core.model.MangaSource
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.parser.ParserMangaRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {

	private var pendingResult = RESULT_CANCELED
	private var isPageLoaded = false
	private var pageLoadedContinuation: ((Unit) -> Unit)? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var settings: AppSettings

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		cfClient = CloudFlareClient(cookieJar, this, adBlock, url)
		viewBinding.webView.webViewClient = cfClient
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				onTitleChanged(getString(R.string.loading_), url)
				viewBinding.webView.loadUrl(url)
			}
			if (settings.isCloudflareAutoSolverEnabled) {
				tryToSolve()
			}
		}
	}

	/**
	 * Waiting for Cloudflare page is initialized in WebView
	 * onPageLoaded callback from CloudFlareClient
	 */
	private suspend fun waitForPageLoaded() {
		if (isPageLoaded) return
		suspendCancellableCoroutine { cont ->
			pageLoadedContinuation = { cont.resume(Unit) }
			cont.invokeOnCancellation { pageLoadedContinuation = null }
		}
	}

	/**
	 * Automatically solve Cloudflare using JS injection and Android touch events.
	 * Should work on every devices, every WebView versions, idk...
	 *
	 * Flow:
	 * 1. Wait for the page to finish loading (callback-driven)
	 * 2. Wait for CF challenge to render completely
	 * 3. Call CloudflareSolver.solve(webView):
	 *    - evaluateJavascript → Find iframe CF → getBoundingClientRect
	 *    - dispatchTouchEvent → Tap on the checkbox
	 *    - Verify → Check cf-turnstile-response OR challenge disappears
	 * 4. If successful → Reload WebView → checkClearance → onCheckPassed()
	 * 5. If it fails → The user solves it manually (with a Toast to warn)
	 */
	private fun tryToSolve() {
		lifecycleScope.launch {
			try {
				waitForPageLoaded()
				delay(CF_CHALLENGE_RENDER_DELAY.milliseconds)
				val solved = CloudflareSolver.solve(viewBinding.webView)
				if (solved) {
					delay(CLEARANCE_CHECK_DELAY.milliseconds)
					viewBinding.webView.reload()
				} else {
					Snackbar.make(viewBinding.webView, R.string.auto_solver_failed, Snackbar.LENGTH_LONG).show()
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		setResult(pendingResult)
		super.finish()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
		if (!isPageLoaded) {
			isPageLoaded = true
			pageLoadedContinuation?.invoke(Unit)
			pageLoadedContinuation = null
		}
	}

	override fun onLoopDetected() {
		restartCheck()
	}

	override fun onCheckPassed() {
		pendingResult = RESULT_OK
		lifecycleScope.launch {
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			isPageLoaded = false
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				viewBinding.webView.loadUrl(targetUrl.toString())
				if (settings.isCloudflareAutoSolverEnabled) {
					tryToSolve()
				}
			}
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		private const val CF_CHALLENGE_RENDER_DELAY = 2000L
		private const val CLEARANCE_CHECK_DELAY = 1500L
	}
}
