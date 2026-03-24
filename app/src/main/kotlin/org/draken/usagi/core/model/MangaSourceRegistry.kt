package org.draken.usagi.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.CopyOnWriteArrayList

object MangaSourceRegistry {
    val sources: MutableList<MangaSource> = CopyOnWriteArrayList()

    @Volatile
    var version: Int = 0
        private set

    val entries: List<MangaSource>
    	get() = sources

    val updates = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun incrementVersion() {
        version++
    }
}
