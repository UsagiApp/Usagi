package org.draken.usagi.settings.sources.catalog

import android.content.Context
import android.view.View
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.list.fastscroll.FastScroller
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.loadingStateAD
import org.draken.usagi.list.ui.model.ListModel

class SourcesCatalogAdapter(
	nativeListener: OnListItemClickListener<SourceCatalogItem.Source>,
	onExtensionClick: (SourceCatalogItem.Extension, View) -> Unit,
	onExtensionInstall: (SourceCatalogItem.Extension, View) -> Unit,
	onExtensionUninstall: (SourceCatalogItem.Extension, View) -> Unit,
	onExtensionSideload: (SourceCatalogItem.Extension, View) -> Unit,
) : BaseListAdapter<ListModel>(),
	FastScroller.SectionIndexer {
	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceCatalogItemSourceAD(nativeListener))
		addDelegate(
			ListItemType.INFO,
			sourceCatalogItemExtensionAD(
				onExtensionClick,
				onExtensionInstall,
				onExtensionUninstall,
				onExtensionSideload,
			),
		)
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

	override fun getSectionText(
		context: Context,
		position: Int,
	): CharSequence? =
		when (val item = items.getOrNull(position)) {
			is SourceCatalogItem.Source -> item.source.getTitle(context).take(1)
			is SourceCatalogItem.Extension -> item.displayName.take(1)
			else -> null
		}
}
