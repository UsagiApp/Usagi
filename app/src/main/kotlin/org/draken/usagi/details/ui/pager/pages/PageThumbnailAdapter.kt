package org.draken.usagi.details.ui.pager.pages

import android.content.Context
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.list.fastscroll.FastScroller
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.listHeaderAD
import org.draken.usagi.list.ui.model.ListModel

class PageThumbnailAdapter(
	clickListener: OnListItemClickListener<PageThumbnail>,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.PAGE_THUMB, pageThumbnailAD(clickListener))
		addDelegate(ListItemType.HEADER, listHeaderAD(null))
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
