package org.draken.usagi.filter.ui.external

import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Implemented by Mihon-backed repositories ([MangaRepository] and its lazy proxy) to expose the
 * source's dynamic [FilterList]. Lets [org.draken.usagi.filter.ui.FilterCoordinator] detect a
 * dynamic-filter source and load its default filters without leaking Mihon types into the core
 * `MangaRepository` interface.
 *
 * UI and main structures for [ExternalMangaRepository] should follow this interface.
 */
interface FilterHost {

	/** True when this repository serves a Mihon source whose filters should use the dynamic filter UI. */
	val isDynamicFiltersSupported: Boolean

	/** Returns a fresh [FilterList] in its default state (a new instance on every call). */
	suspend fun loadFilterList(): FilterList
}
