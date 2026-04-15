package org.draken.usagi.settings.sources.catalog

import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoSource
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

data class InstallableTachiyomiSource(
	val repoSource: TachiyomiRepoSource,
) : MangaSource {
	override val name: String
		get() = "tachiyomi_repo:${repoSource.repoOwnerTag}:${repoSource.extensionPackageName}:${repoSource.sourceId}"

	override val title: String
		get() = repoSource.sourceName

	override val locale: String
		get() = repoSource.sourceLang

	override val contentType: ContentType
		get() = if (repoSource.isNsfwSource) ContentType.HENTAI else ContentType.MANGA
}
