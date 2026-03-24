package org.koitharu.kotatsu.parsers.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.network.RateLimitHelper
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An OkHttp interceptor that enforces rate limiting across all requests.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds` =>  5 requests per second
 * - `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @throws TooManyRequestExceptions when rate limit is exceeded with retry delay information
 */
public fun OkHttpClient.Builder.rateLimit(
	permits: Int,
	period: Duration = 1.seconds
): OkHttpClient.Builder = rateLimit(permits, period) { true }

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `url = "https://api.manga.example"`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `url = "https://cdn.manga.example/image"`, `permits = 10`, `period = 2.minutes`  =>  10 requests per 2 minutes to any url with host "cdn.manga.example"
 *
 * @param url [String]      The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @throws TooManyRequestExceptions when rate limit is exceeded with retry delay information
 */
public fun OkHttpClient.Builder.rateLimit(
	url: String,
	permits: Int,
	period: Duration = 1.seconds
): OkHttpClient.Builder = rateLimit(url.toHttpUrl(), permits, period)

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `httpUrl = "https://api.manga.example".toHttpUrlOrNull()`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `httpUrl = "https://cdn.manga.example/image".toHttpUrlOrNull()`, `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes to any url with host "cdn.manga.example
 *
 * @param httpUrl [HttpUrl] The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @throws TooManyRequestExceptions when rate limit is exceeded with retry delay information
 */
public fun OkHttpClient.Builder.rateLimit(
	httpUrl: HttpUrl,
	permits: Int,
	period: Duration = 1.seconds
): OkHttpClient.Builder = rateLimit(permits, period) { it.host == httpUrl.host }

/**
 * An OkHttp interceptor that enforces conditional rate limiting based on a given condition.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds`, `shouldLimit = { it.host == "api.manga.example" }` => 5 requests per second to any url with host "api.manga.example".
 * - `permits = 10`, `period = 2.minutes`, `shouldLimit = { it.encodedPath.startsWith("/images/") }` => 10 requests per 2 minutes to paths starting with "/images/".
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @param shouldLimit       A predicate to determine whether the rate limit should apply to a given request.
 * @throws TooManyRequestExceptions when rate limit is exceeded with retry delay information
 */
public fun OkHttpClient.Builder.rateLimit(
	permits: Int,
	period: Duration = 1.seconds,
	shouldLimit: (HttpUrl) -> Boolean
): OkHttpClient.Builder {
	return addInterceptor(RateLimitHelper(permits, period, shouldLimit))
}
