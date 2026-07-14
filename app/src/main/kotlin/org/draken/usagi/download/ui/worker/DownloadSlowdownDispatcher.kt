package org.draken.usagi.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.delay
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.parser.MangaParserRepository
import org.draken.usagi.core.parser.tachiyomi.ExternalMangaRepository
import tsuki.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {
	private val timeMap = MutableObjectLongMap<MangaSource>()
	private val defaultDelay = 1_600L

	suspend fun delay(source: MangaSource) {
		if (!isSlowdownEnabled(source)) return
		val lastRequest = synchronized(timeMap) {
			val res = timeMap.getOrDefault(source, 0L)
			timeMap[source] = SystemClock.elapsedRealtime()
			res
		}
		if (lastRequest != 0L) {
			delay(lastRequest + defaultDelay - SystemClock.elapsedRealtime())
		}
	}

	private fun isSlowdownEnabled(source: MangaSource): Boolean = when (val repo = mangaRepositoryFactory.create(source)) {
		is MangaParserRepository -> repo.isSlowdownEnabled()
		is ExternalMangaRepository -> repo.isSlowdownEnabled()
		else -> false
	}
}
