package org.draken.usagi.settings.sources.adapter

import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.core.image.CoilImageView
import org.draken.usagi.core.ui.ReorderableListAdapter
import org.draken.usagi.settings.sources.model.SourceConfigItem

class SourceConfigAdapter(
	listener: SourceConfigListener,
) : ReorderableListAdapter<SourceConfigItem>() {
	init {
		with(delegatesManager) {
			addDelegate(sourceConfigItemDelegate2(listener))
			addDelegate(sourceConfigEmptySearchDelegate())
			addDelegate(sourceConfigTipDelegate(listener))
		}
	}

	override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
		holder.itemView.findViewById<CoilImageView>(R.id.imageView_icon)?.disposeImage()
		super.onViewRecycled(holder)
	}
}
