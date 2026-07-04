package org.draken.usagi.tracker.domain.model

import tsuki.exception.TooManyRequestExceptions
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.util.ifZero

sealed interface MangaUpdates {

	val manga: Manga

	data class Success(
		override val manga: Manga,
		val branch: String?,
		val newChapters: List<MangaChapter>,
		val isValid: Boolean,
	) : MangaUpdates {

		fun isNotEmpty() = newChapters.isNotEmpty()

		fun lastChapterDate(): Long {
			val lastChapter = newChapters.lastOrNull()
			return lastChapter?.uploadDate?.ifZero { System.currentTimeMillis() }
				?: (manga.chapters?.lastOrNull()?.uploadDate ?: 0L)
		}

		fun lastChapterId(): Long {
			return manga.getChapters(branch).maxByOrNull { it.number }?.id
				?: manga.chapters?.maxByOrNull { it.number }?.id
				?: 0L
		}
	}

	data class Failure(
		override val manga: Manga,
		val error: Throwable?,
	) : MangaUpdates {

		fun shouldRetry() = error is TooManyRequestExceptions
	}
}
