package org.draken.usagi.list.domain

object ReadingProgressCalculator {
	fun calculate(
		chapterIndex: Int,
		chaptersCount: Int,
		pageIndex: Int,
		pagesCount: Int,
	): Float {
		if (chaptersCount <= 0 || chapterIndex !in 0 until chaptersCount) {
			return ReadingProgress.PROGRESS_NONE
		}
		if (pagesCount <= 0 || pageIndex !in 0 until pagesCount) {
			return ReadingProgress.PROGRESS_NONE
		}
		val chapterWeight = 1f / chaptersCount
		val pageProgress = (pageIndex + 1) / pagesCount.toFloat()
		return chapterWeight * chapterIndex + chapterWeight * pageProgress
	}
}
