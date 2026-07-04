package org.draken.usagi.list.ui.model

import org.draken.usagi.core.ui.model.MangaOverride
import tsuki.model.Manga

data class MangaCompactListModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	val subtitle: String,
	override val counter: Int,
) : MangaListModel()
