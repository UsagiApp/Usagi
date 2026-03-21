package org.draken.usagi.history.ui

import android.content.Context
import org.draken.usagi.core.ui.list.fastscroll.FastScroller
import org.draken.usagi.list.ui.adapter.MangaListAdapter
import org.draken.usagi.list.ui.adapter.MangaListListener
import org.draken.usagi.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
) : MangaListAdapter(listener, sizeResolver), FastScroller.SectionIndexer {

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
