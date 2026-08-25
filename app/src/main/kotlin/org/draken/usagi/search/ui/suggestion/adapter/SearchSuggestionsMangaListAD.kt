package org.draken.usagi.search.ui.suggestion.adapter

import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.hannesdorfmann.adapterdelegates4.AsyncListDifferDelegationAdapter
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.ui.list.decor.SpacingItemDecoration
import org.draken.usagi.core.util.RecyclerViewScrollCallback
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.databinding.ItemSearchSuggestionMangaGridBinding
import org.draken.usagi.list.ui.model.MangaGridModel
import org.draken.usagi.search.ui.suggestion.SearchSuggestionListener
import org.draken.usagi.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionMangaListAD(listener: SearchSuggestionListener) =
	adapterDelegate<SearchSuggestionItem.MangaList, SearchSuggestionItem>(R.layout.item_search_suggestion_manga_list) {
		val adapter =
			AsyncListDifferDelegationAdapter(
				SuggestionMangaDiffCallback(),
				searchSuggestionMangaGridAD(listener),
			)
		val recyclerView = itemView as RecyclerView
		recyclerView.adapter = adapter
		val spacing = context.resources.getDimensionPixelOffset(R.dimen.search_suggestions_manga_spacing)
		recyclerView.updatePadding(
			left = recyclerView.paddingLeft - spacing,
			right = recyclerView.paddingRight - spacing,
		)
		recyclerView.addItemDecoration(SpacingItemDecoration(spacing, withBottomPadding = true))
		val scrollResetCallback = RecyclerViewScrollCallback(recyclerView, 0, 0)

		bind {
			adapter.setItems(item.items, scrollResetCallback)
		}
	}

private fun searchSuggestionMangaGridAD(listener: SearchSuggestionListener) =
	adapterDelegateViewBinding<MangaGridModel, MangaGridModel, ItemSearchSuggestionMangaGridBinding>(
		{ layoutInflater, parent -> ItemSearchSuggestionMangaGridBinding.inflate(layoutInflater, parent, false) },
	) {
		itemView.setOnClickListener {
			listener.onMangaClick(item.manga)
		}

		bind {
			itemView.setTooltipCompat(item.title)
			binding.imageViewCover.setImageAsync(item.coverUrl, item.source)
			binding.textViewTitle.text = item.title
			with(binding.icons) {
				clearIcons()
				if (item.isSaved) addIcon(R.drawable.ic_storage)
				if (item.isFavorite) addIcon(R.drawable.ic_heart_outline)
				isVisible = iconsCount > 0
			}
			binding.badge.number = item.counter
			binding.badge.isVisible = item.counter > 0
		}
	}

private class SuggestionMangaDiffCallback : DiffUtil.ItemCallback<MangaGridModel>() {
	override fun areItemsTheSame(
		oldItem: MangaGridModel,
		newItem: MangaGridModel,
	): Boolean = oldItem.id == newItem.id

	override fun areContentsTheSame(
		oldItem: MangaGridModel,
		newItem: MangaGridModel,
	): Boolean = oldItem.title == newItem.title && oldItem.coverUrl == newItem.coverUrl
}
