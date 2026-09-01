package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.core.image.CoilImageView
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.list.fastscroll.FastScroller
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.loadingStateAD
import org.draken.usagi.list.ui.model.ListModel

class SourcesCatalogAdapter(
	listener: OnListItemClickListener<SourceCatalogItem.Source>,
) : BaseListAdapter<ListModel>(),
	FastScroller.SectionIndexer {
	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceCatalogItemSourceAD(listener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

	override fun getSectionText(
		context: Context,
		position: Int,
	): CharSequence? = (items.getOrNull(position) as? SourceCatalogItem.Source)?.source?.getTitle(context)?.take(1)

	override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
		holder.itemView.findViewById<CoilImageView>(R.id.imageView_icon)?.disposeImage()
		super.onViewRecycled(holder)
	}
}
