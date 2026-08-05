package org.draken.usagi.core.parser

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.draken.usagi.BuildConfig
import org.draken.usagi.core.network.MangaHttpClient
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.prefs.AppSettings
import tsuki.model.MangaSource
import tsuki.util.await
import tsuki.util.runCatchingCancellable
import javax.inject.Inject

class MirrorSwitcher
	@Inject
	constructor(
		private val settings: AppSettings,
		@MangaHttpClient private val okHttpClient: OkHttpClient,
		private val cookieJar: MutableCookieJar,
	) {
		private val blacklist = mutableSetOf<MangaSource>()
		private val mutex: Mutex = Mutex()

		val isEnabled: Boolean
			get() = settings.isMirrorSwitchingEnabled

		suspend fun <T : Any> trySwitchMirror(
			repository: MangaParserRepository,
			loader: suspend () -> T?,
		): T? {
			val source = repository.source
			if (!isEnabled || source in blacklist) {
				return null
			}
			val availableMirrors = repository.domains
			val currentHost = repository.domain
			if (availableMirrors.size <= 1 || currentHost !in availableMirrors) {
				return null
			}
			mutex.withLock {
				if (source in blacklist) {
					return null
				}
				logd { "Looking for mirrors for $source..." }
				findRedirect(repository)?.let { mirror ->
					val oldHost = repository.domain
					repository.domain = mirror
					if (settings.isTransferCookiesOnRedirectEnabled) {
						transferCookies(oldHost, mirror)
					}
					runCatchingCancellable {
						loader()?.takeIfValid()
					}.getOrNull()?.let {
						logd { "Found redirect for $source: $mirror" }
						return it
					}
				}
				for (mirror in availableMirrors) {
					repository.domain = mirror
					runCatchingCancellable {
						loader()?.takeIfValid()
					}.getOrNull()?.let {
						logd { "Found mirror for $source: $mirror" }
						return it
					}
				}
				repository.domain = currentHost // rollback
				blacklist.add(source)
				logd { "$source blacklisted" }
				return null
			}
		}

		suspend fun findRedirect(repository: MangaParserRepository): String? {
			if (!isEnabled) {
				return null
			}
			val currentHost = repository.domain
			val newHost =
				okHttpClient
					.newCall(
						Request
							.Builder()
							.url("https://$currentHost")
							.head()
							.build(),
					).await()
					.use {
						if (it.isSuccessful) {
							it.request.url.host
						} else {
							null
						}
					}
			return if (newHost != currentHost) {
				newHost
			} else {
				null
			}
		}

		private fun transferCookies(oldDomain: String, newDomain: String) {
			try {
				val oldUrl = "https://$oldDomain".toHttpUrl()
				val newUrl = "https://$newDomain".toHttpUrl()
				val cookies = cookieJar.loadForRequest(oldUrl)
				if (cookies.isNotEmpty()) {
					cookieJar.saveFromResponse(newUrl, cookies)
					logd { "Transferred ${cookies.size} cookies from $oldDomain to $newDomain" }
				}
			} catch (e: Exception) {
				logd { "Failed to transfer cookies: ${e.message}" }
			}
		}

		private fun <T : Any> T.takeIfValid() =
			takeIf {
				when (it) {
					is Collection<*> -> it.isNotEmpty()
					else -> true
				}
			}

		private companion object {
			const val TAG = "MirrorSwitcher"

			inline fun logd(message: () -> String) {
				if (BuildConfig.DEBUG) {
					Log.d(TAG, message())
				}
			}
		}
	}
