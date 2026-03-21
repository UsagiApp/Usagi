package org.draken.usagi.search.ui.suggestion.adapter

import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.search.ui.suggestion.SearchSuggestionListener
import org.draken.usagi.search.ui.suggestion.model.SearchSuggestionItem

const val SEARCH_SUGGESTION_ITEM_TYPE_QUERY = 0

class SearchSuggestionAdapter(
	listener: SearchSuggestionListener,
) : BaseListAdapter<SearchSuggestionItem>() {

	init {
		delegatesManager
			.addDelegate(SEARCH_SUGGESTION_ITEM_TYPE_QUERY, searchSuggestionQueryAD(listener))
			.addDelegate(searchSuggestionSourceAD(listener))
			.addDelegate(searchSuggestionSourceTipAD(listener))
			.addDelegate(searchSuggestionTagsAD(listener))
			.addDelegate(searchSuggestionMangaListAD(listener))
			.addDelegate(searchSuggestionQueryHintAD(listener))
			.addDelegate(searchSuggestionAuthorAD(listener))
			.addDelegate(searchSuggestionTextAD())
	}
}
