package org.koitharu.kotatsu.parsers.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.network.utils.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

public class RateLimitHelper(
	private val permits: Int,
	private val period: Duration,
	private val shouldLimit: (HttpUrl) -> Boolean
) : Interceptor {

	private val limiters = ConcurrentHashMap<String, RateLimiter>()
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()

		if (!shouldLimit(request.url)) {
			return chain.proceed(request)
		}

		val host = request.url.host
		val limiter = limiters.computeIfAbsent(host) {
			RateLimiter(permits, period.inWholeMilliseconds)
		}

		val timestamp = limiter.acquire(
			call = chain.call(),
			url = request.url.toString(),
		)
		val response = chain.proceed(request)

		if (response.networkResponse == null) {
			limiter.release(timestamp)
		}

		return response
	}
}
