package org.draken.usagi.details.domain

import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.isLocal
import org.draken.usagi.core.os.NetworkState
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.draken.usagi.list.domain.ReadingProgressCalculator
import org.draken.usagi.local.data.LocalMangaRepository
import tsuki.model.Manga
import javax.inject.Inject

class ProgressUpdateUseCase
	@Inject
	constructor(
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val database: MangaDatabase,
		private val localMangaRepository: LocalMangaRepository,
		private val networkState: NetworkState,
	) {
		data class Result(
			val progress: Float,
			val manga: Manga?,
		)

		suspend operator fun invoke(manga: Manga): Float = refresh(manga).progress

		suspend fun refresh(
			manga: Manga,
			forceSourceRefresh: Boolean = false,
		): Result {
			val history = database.getHistoryDao().find(manga.id) ?: return Result(PROGRESS_NONE, null)
			val seed =
				if (manga.isLocal) {
					localMangaRepository.getRemoteManga(manga) ?: manga
				} else {
					manga
				}
			if (!seed.isLocal && !networkState.value) {
				return Result(PROGRESS_NONE, null)
			}
			val repo = mangaRepositoryFactory.create(seed.source)
			val details =
				if (forceSourceRefresh || manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
					repo.getDetails(seed)
				} else {
					seed
				}
			val chapter = details.findChapterById(history.chapterId) ?: return Result(PROGRESS_NONE, details)
			val chapters = details.getChapters(chapter.branch)
			val chapterRepo =
				if (repo.source == chapter.source) {
					repo
				} else {
					mangaRepositoryFactory.create(chapter.source)
				}
			val chaptersCount = chapters.size
			if (chaptersCount == 0) {
				return Result(PROGRESS_NONE, details)
			}
			val chapterIndex = chapters.indexOfFirst { x -> x.id == history.chapterId }
			val result =
				ReadingProgressCalculator.calculate(
					chapterIndex = chapterIndex,
					chaptersCount = chaptersCount,
					pageIndex = history.page,
					pagesCount = chapterRepo.getPages(chapter).size,
				)
			if (result == PROGRESS_NONE) {
				return Result(PROGRESS_NONE, details)
			}
			if (result != history.percent || chaptersCount != history.chaptersCount) {
				database.getHistoryDao().update(
					history.copy(
						chapterId = chapter.id,
						percent = result,
						chaptersCount = chaptersCount,
					),
				)
			}
			return Result(result, details)
		}
	}
