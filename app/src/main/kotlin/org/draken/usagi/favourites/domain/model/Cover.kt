package org.draken.usagi.favourites.domain.model

import org.draken.usagi.core.model.MangaSource

data class Cover(
	val url: String?,
	val source: String,
) {
	val mangaSource by lazy { MangaSource(source) }
}
