package org.draken.usagi.list.ui.adapter

import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.google.android.material.badge.BadgeDrawable
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.databinding.ItemHeaderBinding
import org.draken.usagi.list.ui.model.ListHeader
import org.draken.usagi.list.ui.model.ListModel

fun listHeaderAD(
	listener: ListHeaderClickListener?,
) = adapterDelegateViewBinding<ListHeader, ListModel, ItemHeaderBinding>(
	{ inflater, parent -> ItemHeaderBinding.inflate(inflater, parent, false) },
) {
	var badge: BadgeDrawable? = null

	if (listener != null) {
		binding.buttonMore.setOnClickListener {
			listener.onListHeaderClick(item, it)
		}
	}

	bind {
		binding.textViewTitle.text = item.getText(context)
		if (item.buttonTextRes == 0) {
			binding.buttonMore.isInvisible = true
			binding.buttonMore.text = null
			binding.buttonMore.clearBadge(badge)
		} else {
			binding.buttonMore.setText(item.buttonTextRes)
			binding.buttonMore.isVisible = true
			badge = itemView.bindBadge(badge, item.badge)
		}
	}
}
