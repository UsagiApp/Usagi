package org.draken.usagi.list.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressCalculatorTest {
	@Test
	fun `calculate includes completed chapters and current page`() {
		assertProgress(0.375f, ReadingPosition(chapterIndex = 1, pageIndex = 4))
	}

	@Test
	fun `calculate returns no progress for unusable positions`() {
		val invalidInputs =
			listOf(
				ReadingPosition(chapterIndex = -1),
				ReadingPosition(chapterIndex = 4),
				ReadingPosition(chaptersCount = 0),
				ReadingPosition(pageIndex = -1),
				ReadingPosition(pageIndex = 10),
				ReadingPosition(pagesCount = 0),
			)

		invalidInputs.forEach { input ->
			assertProgress(ReadingProgress.PROGRESS_NONE, input)
		}
	}

	@Test
	fun `completion uses one public threshold`() {
		assertEquals(true, ReadingProgress.isCompleted(ReadingProgress.COMPLETION_THRESHOLD))
		assertEquals(false, ReadingProgress.isCompleted(ReadingProgress.COMPLETION_THRESHOLD - 0.00001f))
	}

	@Test
	fun `calculate updates progress when chapter count grows`() {
		val position = ReadingPosition(chapterIndex = 1, chaptersCount = 2, pageIndex = 9)
		assertProgress(1f, position)
		assertProgress(0.5f, position.copy(chaptersCount = 4))
	}

	private fun assertProgress(
		expected: Float,
		input: ReadingPosition,
	) = assertEquals(
		input.toString(),
		expected,
		ReadingProgressCalculator.calculate(input.chapterIndex, input.chaptersCount, input.pageIndex, input.pagesCount),
	)

	private data class ReadingPosition(
		val chapterIndex: Int = 0,
		val chaptersCount: Int = 4,
		val pageIndex: Int = 0,
		val pagesCount: Int = 10,
	)
}
