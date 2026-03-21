package org.draken.usagi.details.ui.scrobbling

import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.list.ui.model.ListModel

class ScrollingInfoAdapter(
	router: AppRouter,
) : BaseListAdapter<ListModel>() {

	init {
		delegatesManager.addDelegate(scrobblingInfoAD(router))
	}
}
