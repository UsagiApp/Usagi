package org.draken.usagi.core.model

import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.CopyOnWriteArrayList

object MangaSourceRegistry {
    val sources: MutableList<MangaSource> = CopyOnWriteArrayList()

    val entries: List<MangaSource>
    	get() = sources
}
