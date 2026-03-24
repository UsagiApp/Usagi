package org.koitharu.kotatsu.parsers.network

internal object CommonHeaders {

	const val REFERER: String = "Referer"
	const val USER_AGENT: String = "User-Agent"
	const val ACCEPT: String = "Accept"
	const val ACCEPT_LANGUAGE: String = "Accept-Language"
	const val ACCEPT_CHARSET: String = "Accept-Charset"
	const val ACCEPT_ENCODING: String = "Accept-Encoding"

	const val CONTENT_TYPE: String = "Content-Type"
	const val CONTENT_LENGTH: String = "Content-Length"
	const val CONTENT_DISPOSITION: String = "Content-Disposition"
	const val CONTENT_ENCODING: String = "Content-Encoding"
	const val CONTENT_LANGUAGE: String = "Content-Language"

	const val AUTHORIZATION: String = "Authorization"
	const val PROXY_AUTHORIZATION: String = "Proxy-Authorization"

	const val COOKIE: String = "Cookie"
	const val SET_COOKIE: String = "Set-Cookie"

	const val CACHE_CONTROL: String = "Cache-Control"
	const val PRAGMA: String = "Pragma"
	const val EXPIRES: String = "Expires"

	const val LAST_MODIFIED: String = "Last-Modified"
	const val IF_MODIFIED_SINCE: String = "If-Modified-Since"
	const val ETAG: String = "ETag"
	const val IF_NONE_MATCH: String = "If-None-Match"

	const val LOCATION: String = "Location"
	const val HOST: String = "Host"
	const val ORIGIN: String = "Origin"
	const val CONNECTION: String = "Connection"

	const val RANGE: String = "Range"
	const val CONTENT_RANGE: String = "Content-Range"

	const val RETRY_AFTER: String = "Retry-After"
	const val SERVER: String = "Server"
	const val DATE: String = "Date"

	const val X_FORWARDED_FOR: String = "X-Forwarded-For"
	const val X_REAL_IP: String = "X-Real-IP"
	const val X_REQUESTED_WITH: String = "X-Requested-With"

	/* Fetch metadata headers */
	const val SEC_FETCH_DEST: String = "Sec-Fetch-Dest"
	const val SEC_FETCH_MODE: String = "Sec-Fetch-Mode"
	const val SEC_FETCH_SITE: String = "Sec-Fetch-Site"
	const val SEC_FETCH_USER: String = "Sec-Fetch-User"

	/* Client hints headers */
	const val SEC_CH_UA: String = "Sec-Ch-Ua"
	const val SEC_CH_UA_MOBILE: String = "Sec-Ch-Ua-Mobile"
	const val SEC_CH_UA_PLATFORM: String = "Sec-Ch-Ua-Platform"

	/* Client API headers */
	const val X_CLIENT_TS: String = "x-client-ts"
	const val X_CLIENT_SIG: String = "x-client-sig"

	/* API specific headers */
	const val X_APP_KEY: String = "x-app-key"
	const val X_APP_ORIGIN: String = "x-app-origin"
	const val X_WM_REQUEST_TIME: String = "x-wm-request-time"
	const val X_WM_ACCESS_KEY: String = "x-wm-accses-key"
	const val X_WM_REQUEST_SIGNATURE: String = "x-wm-request-signature"

	/* Other browser headers */
	const val UPGRADE_INSECURE_REQUESTS: String = "Upgrade-Insecure-Requests"

	/* Some custom headers */
	const val MANGA_SOURCE: String = "X-Manga-Source"
	const val TOKEN: String = "Token"
}
