package org.draken.usagi.scrobbling.common.ui.config.adapter

import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.emptyStateListAD
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.scrobbling.common.domain.model.ScrobblingInfo

class ScrobblingMangaAdapter(
	clickListener: OnListItemClickListener<ScrobblingInfo>,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.HEADER, scrobblingHeaderAD())
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
		addDelegate(ListItemType.MANGA_SCROBBLING, scrobblingMangaAD(clickListener))
	}
}
