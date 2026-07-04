package org.draken.usagi.core.model

import tsuki.model.MangaSource

data class SourceSnapshot(
	val sources: List<MangaSource>,
	val version: Int,
	val byName: Map<String, MangaSource>,
	val byShortName: Map<String, MangaSource>,
) {
	companion object {
		val EMPTY = SourceSnapshot(
			sources = emptyList(),
			version = 0,
			byName = emptyMap(),
			byShortName = emptyMap(),
		)
	}
}
