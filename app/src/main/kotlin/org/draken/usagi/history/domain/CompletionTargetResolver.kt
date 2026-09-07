package org.draken.usagi.history.domain

import org.draken.usagi.core.model.MangaHistory
import org.draken.usagi.core.model.getPreferredBranch
import tsuki.model.Manga
import tsuki.model.MangaChapter

object CompletionTargetResolver {
	fun resolve(
		manga: Manga,
		history: MangaHistory?,
	): MangaChapter {
		val branch = manga.getPreferredBranch(history)
		return manga.getChapters(branch).lastOrNull()
			?: error("Preferred branch has no chapters")
	}
}
