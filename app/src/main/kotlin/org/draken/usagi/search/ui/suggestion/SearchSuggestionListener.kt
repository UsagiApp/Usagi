package org.draken.usagi.search.ui.suggestion

import android.text.TextWatcher
import android.widget.TextView
import org.draken.usagi.search.domain.SearchKind
import tsuki.model.Manga
import tsuki.model.MangaSource
import tsuki.model.MangaTag

interface SearchSuggestionListener :
	TextWatcher,
	TextView.OnEditorActionListener {
	fun onMangaClick(manga: Manga)

	fun onQueryClick(
		query: String,
		kind: SearchKind,
		submit: Boolean,
	)

	fun onSourceToggle(
		source: MangaSource,
		isEnabled: Boolean,
	)

	fun onSourceClick(source: MangaSource)

	fun onTagClick(tag: MangaTag)
}
