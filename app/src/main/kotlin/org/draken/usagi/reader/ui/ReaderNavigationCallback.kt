package org.draken.usagi.reader.ui

import org.draken.usagi.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.draken.usagi.reader.ui.pager.ReaderPage

interface ReaderNavigationCallback {

	fun onPageSelected(page: ReaderPage): Boolean

	fun onChapterSelected(chapter: MangaChapter): Boolean

	fun onBookmarkSelected(bookmark: Bookmark): Boolean = onPageSelected(
		ReaderPage(bookmark.toMangaPage(), bookmark.page, bookmark.chapterId),
	)
}
