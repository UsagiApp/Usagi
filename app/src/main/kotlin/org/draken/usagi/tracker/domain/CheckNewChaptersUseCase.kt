package org.draken.usagi.tracker.domain

import coil3.request.CachePolicy
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.getPreferredBranch
import org.draken.usagi.core.model.isLocal
import org.draken.usagi.core.parser.CachingMangaRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.MultiMutex
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.toInstantOrNull
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.local.data.LocalMangaRepository
import org.draken.usagi.tracker.domain.model.MangaTracking
import org.draken.usagi.tracker.domain.model.MangaUpdates
import tsuki.model.Manga
import tsuki.util.findById
import tsuki.util.runCatchingCancellable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
	private val database: MangaDatabase,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates = mutex.withLock(track.manga.id) {
		// re-fetch from repository (db) -> get latest state
		val track = repository.getTrackOrNull(track.manga)?.takeUnless { it.isEmpty() } ?: track
		invokeImpl(track)
	}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga)
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch).sortedBy { it.number }
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastNewChapterIndex = chapters.size - track.newChapters
			val lastChapter = chapters.lastOrNull()
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					track.newChapters == 0 -> 0
					chapterIndex < 0 -> track.newChapters
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> track.newChapters
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		val details = getFullManga(track.manga)
		val updates = compare(track, details, getBranch(details, track.lastChapterId))
		if (repository.saveUpdates(updates)) { updates } else {
			updates.copy(newChapters = emptyList())
		}
	}.getOrElse { error ->
		val updates = MangaUpdates.Failure(
			manga = track.manga,
			error = error,
		)
		repository.saveUpdates(updates)
		updates
	}

	private suspend fun getBranch(manga: Manga, trackChapterId: Long): String? {
		historyRepository.getOne(manga)?.let {
			manga.chapters?.findById(it.chapterId)
		}?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		// fallback
		return manga.getPreferredBranch(null)
	}

	private suspend fun <T> retry(
		times: Int = 3,
		initialDelay: Long = 1000,
		maxDelay: Long = 4000,
		factor: Double = 2.0,
		shouldRetry: (Throwable) -> Boolean = {
			it is java.io.IOException || it is android.os.DeadObjectException
		},
		block: suspend () -> T
	): T {
		var currentDelay = initialDelay
		repeat(times - 1) {
			try {
				return block()
			} catch (e: Throwable) {
				if (!shouldRetry(e)) {
					throw e
				}
				kotlinx.coroutines.delay(currentDelay)
				currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
			}
		}
		return block()
	}

	private suspend fun getFullManga(manga: Manga): Manga = retry {
		if (manga.isLocal) {
			localMangaRepository.getRemoteManga(manga)?.let { fetchDetails(it) } ?: manga
		} else { fetchDetails(manga) }
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		return if (repo is CachingMangaRepository) {
			repo.getDetails(manga, CachePolicy.WRITE_ONLY)
		} else {
			repo.getDetails(manga)
		}
	}

	/**
	 * The main functionality of tracker: check new chapters in [manga] comparing to the [track]
	 */
	private suspend fun compare(track: MangaTracking, manga: Manga, branch: String?): MangaUpdates.Success {
		if (track.isEmpty()) {
			// first check or manga was empty on last check
			val last = historyRepository.getOne(manga)?.chaptersCount ?: 0
			if (last > 0) {
				val chapters = manga.getChapters(branch).sortedBy { it.number }
				if (chapters.isNotEmpty() && chapters.size > last) {
					val new = chapters.size - last
					return MangaUpdates.Success(manga, branch, chapters.takeLast(new), true)
				}
				return MangaUpdates.Success(manga, branch, emptyList(), true)
			}
			return MangaUpdates.Success(manga, branch, emptyList(), false)
		}
		val chapters = requireNotNull(manga.getChapters(branch)).sortedBy { it.number }
		if (chapters.isEmpty()) {
			return MangaUpdates.Success(manga, branch, emptyList(), false)
		}

		val directMatchIndex = chapters.indexOfLast { it.id == track.lastChapterId }
		if (directMatchIndex >= 0) {
			return MangaUpdates.Success(manga, branch, chapters.subList(directMatchIndex + 1, chapters.size), true)
		}

		val cachedChapters = database.getChaptersDao().findAll(manga.id)
		val lastChapterCached = cachedChapters.firstOrNull { it.chapterId == track.lastChapterId }

		if (lastChapterCached != null) {
			val urlMatchIndex = chapters.indexOfLast { it.url == lastChapterCached.url }
			if (urlMatchIndex >= 0) {
				return MangaUpdates.Success(manga, branch, chapters.subList(urlMatchIndex + 1, chapters.size), true)
			}

			val numberMatchIndex = chapters.indexOfLast {
				it.number == lastChapterCached.number && (lastChapterCached.volume <= 0 || it.volume == lastChapterCached.volume)
			}
			if (numberMatchIndex >= 0) {
				return MangaUpdates.Success(manga, branch, chapters.subList(numberMatchIndex + 1, chapters.size), true)
			}

			val newChapters = chapters.filter { it.number > lastChapterCached.number }
			return MangaUpdates.Success(manga, branch, newChapters, true)
		}

		if (cachedChapters.isNotEmpty() && chapters.size > cachedChapters.size) {
			val newCount = chapters.size - cachedChapters.size
			return MangaUpdates.Success(manga, branch, chapters.takeLast(newCount), true)
		}

		if (cachedChapters.isNotEmpty()) {
			return MangaUpdates.Success(manga, branch, emptyList(), true)
		}

		return MangaUpdates.Success(manga, branch, emptyList(), false)
	}
}
