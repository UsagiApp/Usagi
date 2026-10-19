package org.draken.usagi.list.domain

import tsuki.model.Manga

/**
 * Client-side exclusion of manga that carry one of the tags picked in the
 * "Exclude tags from all manga" setting. Matching is exact and case-insensitive.
 */
class ExcludedTags(
	tags: Set<String>,
) {
	private val normalized = tags.mapTo(HashSet(tags.size)) { it.lowercase() }

	val isNotEmpty: Boolean
		get() = normalized.isNotEmpty()

	operator fun contains(manga: Manga): Boolean = normalized.isNotEmpty() && manga.tags.any { it.title.lowercase() in normalized }

	fun filter(manga: List<Manga>): List<Manga> =
		if (normalized.isEmpty()) {
			manga
		} else {
			manga.filterNot { it in this }
		}
}
