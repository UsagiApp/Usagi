package org.draken.usagi.history.domain

import dagger.Reusable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.list.domain.ReadingProgress
import tsuki.model.Manga
import javax.inject.Inject

@Reusable
class MarkAsReadUseCase
	@Inject
	constructor(
		private val historyRepository: HistoryRepository,
		private val mangaRepositoryFactory: MangaRepository.Factory,
	) {
		suspend operator fun invoke(manga: Manga) {
			val detailsRepo = mangaRepositoryFactory.create(manga.source)
			val details =
				if (manga.chapters.isNullOrEmpty()) {
					detailsRepo.getDetails(manga)
				} else {
					manga
				}
			val history = historyRepository.getOne(details)
			val lastChapter = CompletionTargetResolver.resolve(details, history)
			val chapterRepo = mangaRepositoryFactory.create(lastChapter.source)
			val pages = chapterRepo.getPages(lastChapter)
			check(pages.isNotEmpty()) { "Preferred branch final chapter has no pages" }
			historyRepository.addOrUpdate(
				manga = details,
				chapterId = lastChapter.id,
				page = pages.lastIndex,
				scroll = 0,
				percent = ReadingProgress.PROGRESS_COMPLETED,
				force = true,
			)
		}

		suspend operator fun invoke(manga: Collection<Manga>) {
			when (manga.size) {
				0 -> {
					Unit
				}

				1 -> {
					invoke(manga.first())
				}

				else -> {
					supervisorScope {
						manga
							.map {
								launch {
									invoke(it)
								}
							}.joinAll()
					}
				}
			}
		}
	}
