package org.draken.usagi.reader.ui

import org.draken.usagi.bookmarks.domain.Bookmark
import org.draken.usagi.reader.ui.pager.ReaderPage
import tsuki.model.MangaChapter

interface ReaderNavigationCallback {
	fun onPageSelected(page: ReaderPage): Boolean

	fun onChapterSelected(chapter: MangaChapter): Boolean

	fun onBookmarkSelected(bookmark: Bookmark): Boolean =
		onPageSelected(
			ReaderPage(bookmark.toMangaPage(), bookmark.page, bookmark.chapterId),
		)
}
