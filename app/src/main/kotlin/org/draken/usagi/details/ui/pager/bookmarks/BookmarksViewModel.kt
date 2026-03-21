package org.draken.usagi.details.ui.pager.bookmarks

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.bookmarks.domain.Bookmark
import org.draken.usagi.bookmarks.domain.BookmarksRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsStateFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.requireValue
import org.draken.usagi.details.data.MangaDetails
import org.draken.usagi.list.ui.model.EmptyState
import org.draken.usagi.list.ui.model.ListHeader
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.draken.usagi.reader.ui.PageSaveHelper
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
	private val bookmarksRepository: BookmarksRepository,
	settings: AppSettings,
) : BaseViewModel(), FlowCollector<MangaDetails?> {

	private val manga = MutableStateFlow<Manga?>(null)
	val onActionDone = MutableEventFlow<ReversibleAction>()

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	val content: StateFlow<List<ListModel>> = manga.filterNotNull().flatMapLatest { m ->
		bookmarksRepository.observeBookmarks(m)
			.map { mapList(m, it) }
	}.withErrorHandling()
		.filterNotNull()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

	override suspend fun emit(value: MangaDetails?) {
		manga.value = value?.toManga()
	}

	fun removeBookmarks(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handle = bookmarksRepository.removeBookmarks(ids)
			onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
		}
	}

	fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			val m = manga.requireValue()
			val tasks = content.value.mapNotNull {
				if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
				PageSaveHelper.Task(
					manga = m,
					chapterId = it.chapterId,
					pageNumber = it.page + 1,
					page = it.toMangaPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			val msg = if (dest.size == 1) R.string.page_saved else R.string.pages_saved
			onActionDone.call(ReversibleAction(msg, null))
		}
	}

	private fun mapList(manga: Manga, bookmarks: List<Bookmark>): List<ListModel>? {
		val chapters = manga.chapters ?: return null
		val bookmarksMap = bookmarks.groupBy { it.chapterId }
		val result = ArrayList<ListModel>(bookmarks.size + bookmarksMap.size)
		for (chapter in chapters) {
			val b = bookmarksMap[chapter.id]
			if (b.isNullOrEmpty()) {
				continue
			}
			result += ListHeader(chapter)
			result.addAll(b)
		}
		if (result.isEmpty()) {
			result.add(
				EmptyState(
					icon = 0,
					textPrimary = R.string.no_bookmarks_yet,
					textSecondary = R.string.no_bookmarks_summary,
					actionStringRes = 0,
				),
			)
		}
		return result
	}
}
