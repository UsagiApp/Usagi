package org.draken.usagi.alternatives.domain

import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.getPreferredBranch
import org.draken.usagi.core.parser.MangaDataRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.details.domain.ProgressUpdateUseCase
import org.draken.usagi.history.data.HistoryEntity
import org.draken.usagi.history.data.toMangaHistory
import org.draken.usagi.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.draken.usagi.scrobbling.common.domain.Scrobbler
import org.draken.usagi.scrobbling.common.domain.model.ScrobblingStatus
import org.draken.usagi.scrobbling.common.domain.tryScrobble
import org.draken.usagi.tracker.data.TrackEntity
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.util.runCatchingCancellable
import javax.inject.Inject

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
	) {
		val (oldDetails, newDetails) = coroutineScope {
			val oldDeferred = async {
				if (oldManga.chapters.isNullOrEmpty()) {
					runCatchingCancellable {
						mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
					}.getOrDefault(oldManga)
				} else { oldManga }
			}
			val newDeferred = async {
				if (newManga.chapters.isNullOrEmpty()) {
					mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
				} else { newManga }
			}
			oldDeferred.await() to newDeferred.await()
		}
		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		var newHistory: HistoryEntity? = null
		database.withTransaction {
			// replace favorites
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldManga.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newManga.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace history
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			if (oldHistory != null) {
				val history = makeNewHistory(oldDetails, newDetails, oldHistory)
				historyDao.delete(oldDetails.id)
				historyDao.upsert(history)
				newHistory = history
			}
			// track
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						mangaId = newDetails.id,
						lastChapterId = lastChapter?.id ?: 0L,
						newChapters = 0,
						lastCheckTime = System.currentTimeMillis(),
						lastChapterDate = lastChapter?.uploadDate ?: 0L,
						lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
						lastError = null,
					)
				tracksDao.delete(oldDetails.id)
				tracksDao.upsert(newTrack)
			}
		}

		// scrobbling
		val scrobblerJobs = scrobblers.filter { it.isEnabled }.mapNotNull { scrobbler ->
			val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: return@mapNotNull null
			scrobbler to prevInfo
		}
		if (scrobblerJobs.isNotEmpty()) {
			coroutineScope {
				scrobblerJobs.map { (scrobbler, prevInfo) ->
					async {
						runCatchingCancellable {
							scrobbler.unregisterScrobbling(oldDetails.id)
							scrobbler.linkManga(newDetails.id, prevInfo.targetId)
							scrobbler.updateScrobblingInfo(newDetails.id, prevInfo.rating,
								status = prevInfo.status ?: when {
									newHistory == null -> ScrobblingStatus.PLANNED
									newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
									else -> ScrobblingStatus.READING
								}, prevInfo.comment,
							)
							if (newHistory != null) {
								scrobbler.tryScrobble(newDetails, newHistory.chapterId)
							}
						}
					}
				}.awaitAll()
			}
		}
		progressUpdateUseCase(newManga)
	}

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt()
				} else {
					0
				}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val newChapterId =
			checkNotNull(newChapters[newBranch])
				.let {
					val oldChapter = oldChapters[index]
					it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
				}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = PROGRESS_NONE,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun List<MangaChapter>.findByNumber(
		volume: Int,
		number: Float,
	): MangaChapter? =
		if (number <= 0f) {
			null
		} else {
			firstOrNull { it.volume == volume && it.number == number }
		}
}
