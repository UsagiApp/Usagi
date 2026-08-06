package org.draken.usagi.history.domain

import org.draken.usagi.core.model.MangaHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaSource
import java.time.Instant
import org.draken.usagi.core.model.MangaSource as mangaSourceOf

class CompletionTargetResolverTest {
	@Test
	fun `selects the final chapter and source of the history branch`() {
		val sourceA = mangaSourceOf("source-a")
		val sourceB = mangaSourceOf("source-b")
		val branchA = listOf(chapter(1, "A", sourceA), chapter(3, "A", sourceA))
		val branchB = listOf(chapter(2, "B", sourceB), chapter(4, "B", sourceB))
		val manga =
			Manga(
				id = 10,
				title = "Two branches",
				altTitles = emptySet(),
				state = null,
				rating = 0f,
				contentRating = null,
				url = "url",
				publicUrl = "public-url",
				coverUrl = "cover",
				largeCoverUrl = null,
				authors = emptySet(),
				source = sourceA,
				tags = emptySet(),
				chapters = branchA + branchB,
			)
		val history =
			MangaHistory(
				createdAt = Instant.EPOCH,
				updatedAt = Instant.EPOCH,
				chapterId = 2,
				page = 0,
				scroll = 0,
				percent = 0.5f,
				chaptersCount = 2,
			)

		val target = CompletionTargetResolver.resolve(manga, history)

		assertEquals(4L, target.id)
		assertEquals(sourceB, target.source)
	}

	private fun chapter(
		id: Long,
		branch: String,
		source: MangaSource,
	) = MangaChapter(
		id = id,
		title = null,
		number = id.toFloat(),
		volume = 0,
		url = "chapter-$id",
		scanlator = null,
		uploadDate = 0,
		branch = branch,
		source = source,
	)
}
