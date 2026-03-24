package org.koitharu.kotatsu.parsers.util

import org.koitharu.kotatsu.parsers.MangaLoaderContext

public class WebViewHelper(
	private val context: MangaLoaderContext,
) {

	public suspend fun getLocalStorageValue(domain: String, key: String): String? {
		return context.evaluateJs("$SCHEME_HTTPS://$domain/", "window.localStorage.getItem(\"$key\")")
	}

    public suspend fun getUrlValue(url: String, value: String): String? {
        return context.evaluateJs(url, "new URLSearchParams(window.location.search).get('$value');")
    }
}
