package org.draken.usagi.favourites.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.details.domain.ProgressUpdateUseCase
import org.draken.usagi.tracker.domain.CheckNewChaptersUseCase
import org.draken.usagi.tracker.domain.model.MangaUpdates
import tsuki.model.Manga
import tsuki.util.runCatchingCancellable
import javax.inject.Inject

data class FavouriteOrganizerRefreshResult(
	val requested: Int,
	val updated: Int,
	val failed: Int,
)

class RefreshFavouriteOrganizerUseCase
	@Inject
	constructor(
		private val favouritesRepository: FavouritesRepository,
		private val checkNewChaptersUseCase: CheckNewChaptersUseCase,
		private val progressUpdateUseCase: ProgressUpdateUseCase,
		private val database: MangaDatabase,
	) {
		suspend operator fun invoke(scope: FavouriteScope): FavouriteOrganizerRefreshResult =
			coroutineScope {
				val candidates = favouritesRepository.getOrganizerRefreshCandidates(scope, MAX_CANDIDATES)
				val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
				val results =
					candidates
						.map { manga ->
							async {
								semaphore.withPermit { refresh(manga) }
							}
						}.awaitAll()
				FavouriteOrganizerRefreshResult(
					requested = candidates.size,
					updated = results.count { updated -> updated },
					failed = results.count { updated -> !updated },
				)
			}

		private suspend fun refresh(manga: Manga): Boolean =
			runCatchingCancellable {
				val trackingResult = checkNewChaptersUseCase.checkTrackedManga(manga)
				if (trackingResult is MangaUpdates.Failure && trackingResult.error != null) {
					throw trackingResult.error
				}
				val progressSeed =
					when (trackingResult) {
						is MangaUpdates.Success -> trackingResult.manga
						is MangaUpdates.Failure -> manga
					}
				val progressResult =
					progressUpdateUseCase.refresh(
						manga = progressSeed,
						forceSourceRefresh = trackingResult is MangaUpdates.Failure,
					)
				val refreshedManga = progressResult.manga ?: (trackingResult as? MangaUpdates.Success)?.manga
				if (refreshedManga != null && refreshedManga.id == manga.id) {
					database.getMangaDao().updateState(manga.id, refreshedManga.state?.name)
				}
				progressResult.manga != null
			}.getOrDefault(false)

		private companion object {
			const val MAX_CANDIDATES = 50
			const val MAX_CONCURRENT_REQUESTS = 3
		}
	}
