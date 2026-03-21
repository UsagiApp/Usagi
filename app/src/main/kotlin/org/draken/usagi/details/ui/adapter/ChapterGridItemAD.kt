package org.draken.usagi.details.ui.adapter

import android.graphics.Typeface
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.core.ui.list.AdapterDelegateClickListenerAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.getThemeColorStateList
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.databinding.ItemChapterGridBinding
import org.draken.usagi.details.ui.model.ChapterListItem
import org.draken.usagi.list.ui.model.ListModel

fun chapterGridItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterGridBinding>(
	viewBinding = { inflater, parent -> ItemChapterGridBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

	bind { payloads ->
		if (payloads.isEmpty()) {
			binding.textViewTitle.text = item.chapter.numberString() ?: "?"
			itemView.setTooltipCompat(item.chapter.title)
		}
		binding.imageViewNew.isVisible = item.isNew
		binding.imageViewCurrent.isVisible = item.isCurrent
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		binding.imageViewDownloaded.isVisible = item.isDownloaded

		when {
			item.isCurrent -> {
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
			}

			item.isUnread -> {
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewTitle.typeface = Typeface.DEFAULT
			}

			else -> {
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewTitle.typeface = Typeface.DEFAULT
			}
		}
	}
}

