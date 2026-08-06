package org.draken.usagi.list.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressCalculatorTest {
	@Test
	fun `calculate includes completed chapters and current page`() {
		val result =
			ReadingProgressCalculator.calculate(
				chapterIndex = 1,
				chaptersCount = 4,
				pageIndex = 4,
				pagesCount = 10,
			)

		assertEquals(0.375f, result)
	}

	@Test
	fun `calculate returns no progress for unusable positions`() {
		val invalidInputs =
			listOf(
				ReadingPosition(chapterIndex = -1, chaptersCount = 4, pageIndex = 0, pagesCount = 10),
				ReadingPosition(chapterIndex = 4, chaptersCount = 4, pageIndex = 0, pagesCount = 10),
				ReadingPosition(chapterIndex = 0, chaptersCount = 0, pageIndex = 0, pagesCount = 10),
				ReadingPosition(chapterIndex = 0, chaptersCount = 4, pageIndex = -1, pagesCount = 10),
				ReadingPosition(chapterIndex = 0, chaptersCount = 4, pageIndex = 10, pagesCount = 10),
				ReadingPosition(chapterIndex = 0, chaptersCount = 4, pageIndex = 0, pagesCount = 0),
			)

		invalidInputs.forEach { input ->
			assertEquals(
				ReadingProgress.PROGRESS_NONE,
				ReadingProgressCalculator.calculate(
					chapterIndex = input.chapterIndex,
					chaptersCount = input.chaptersCount,
					pageIndex = input.pageIndex,
					pagesCount = input.pagesCount,
				),
			)
		}
	}

	@Test
	fun `completion uses one public threshold`() {
		assertEquals(true, ReadingProgress.isCompleted(ReadingProgress.COMPLETION_THRESHOLD))
		assertEquals(false, ReadingProgress.isCompleted(ReadingProgress.COMPLETION_THRESHOLD - 0.00001f))
	}

	@Test
	fun `calculate updates progress when chapter count grows`() {
		val previous =
			ReadingProgressCalculator.calculate(
				chapterIndex = 1,
				chaptersCount = 2,
				pageIndex = 9,
				pagesCount = 10,
			)
		val recalculated =
			ReadingProgressCalculator.calculate(
				chapterIndex = 1,
				chaptersCount = 4,
				pageIndex = 9,
				pagesCount = 10,
			)

		assertEquals(1f, previous)
		assertEquals(0.5f, recalculated)
	}

	private data class ReadingPosition(
		val chapterIndex: Int,
		val chaptersCount: Int,
		val pageIndex: Int,
		val pagesCount: Int,
	)
}
