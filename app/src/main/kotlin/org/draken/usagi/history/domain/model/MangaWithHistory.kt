package org.draken.usagi.history.domain.model

import org.draken.usagi.core.model.MangaHistory
import tsuki.model.Manga

data class MangaWithHistory(
	val manga: Manga,
	val history: MangaHistory
)
