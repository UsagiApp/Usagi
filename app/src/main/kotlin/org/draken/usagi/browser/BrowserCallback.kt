package org.draken.usagi.browser

interface BrowserCallback : OnHistoryChangedListener {

	fun onLoadingStateChanged(isLoading: Boolean)

	fun onTitleChanged(title: CharSequence, subtitle: CharSequence?)

	fun onPageFinished(url: String) = Unit
}
